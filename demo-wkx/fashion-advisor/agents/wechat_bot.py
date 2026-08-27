"""
WeChatBotService - 微信 ClawBot 穿搭顾问机器人服务
仿照 demo-dxy 的 BotService，将 AI 智能穿搭顾问与微信机器人结合。
管理登录状态、处理微信消息、通过穿搭顾问 Agent 管线返回结果。
"""

import os
import io
import json
import time
import random
import sqlite3
from datetime import datetime


class WeChatBotService:
    """微信穿搭顾问机器人，模拟 iLink 登录流程 + 消息处理"""

    TOOLS = [
        "outfit_consultation",
        "product_search",
        "price_comparison",
        "cart_management",
        "weather_query",
        "body_assessment",
    ]

    TOOL_LABELS = {
        "outfit_consultation": "穿搭咨询",
        "product_search": "商品检索",
        "price_comparison": "智能比价",
        "cart_management": "购物车管理",
        "weather_query": "天气查询",
        "body_assessment": "体型分析",
    }

    def __init__(self, db_path, profile_agent, weather_tool,
                 outfit_composer, product_searcher, price_comparator,
                 cart_manager, intent_router):
        self.db_path = db_path
        self.profile_agent = profile_agent
        self.weather_tool = weather_tool
        self.outfit_composer = outfit_composer
        self.product_searcher = product_searcher
        self.price_comparator = price_comparator
        self.cart_manager = cart_manager
        self.intent_router = intent_router

        self._login_state = "waiting"
        self._login_time = None
        self._bot_id = ""
        self._user_id = ""
        self._qr_token = self._gen_token()
        self._wx_users = {}

    @staticmethod
    def _gen_token():
        return "".join(random.choices("0123456789abcdef", k=16))

    def get_status(self):
        return {
            "loggedIn": self._login_state == "online",
            "connectionStatus": "CONNECTED" if self._login_state == "online" else "DISCONNECTED",
            "llmConfigured": True,
            "tools": self.TOOLS,
            "loginError": None,
            "botId": self._bot_id or "",
            "userId": self._user_id or "",
        }

    def generate_qr_png(self):
        import qrcode
        url = f"https://wx.qq.com/scan?token={self._qr_token}&app=fashion_advisor"
        img = qrcode.make(url)
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return buf.getvalue()

    def trigger_relogin(self):
        self._login_state = "waiting"
        self._login_time = None
        self._bot_id = ""
        self._user_id = ""
        self._qr_token = self._gen_token()

    def simulate_login(self):
        self._login_state = "online"
        self._login_time = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        self._bot_id = "wx_fashion_" + self._qr_token[:12]
        self._user_id = "wx_user_" + "".join(random.choices("0123456789", k=10))

    def process_message(self, wx_user_id, text):
        if self._login_state != "online":
            return {"reply": "机器人尚未上线，请先扫码登录。"}

        if wx_user_id not in self._wx_users:
            self._wx_users[wx_user_id] = self._ensure_user(wx_user_id)

        user_id = self._wx_users[wx_user_id]
        intent = self.intent_router.classify(text)
        profile = self.profile_agent.get_profile(user_id)

        if intent == "outfit_request":
            if not profile:
                return {"reply": "请先在网页端完善个人资料（身高、体重、偏好等），我才能为您生成穿搭方案。"}
            occasion = self._extract_occasion(text)
            city = profile.get("city", "上海") if isinstance(profile, dict) else "上海"
            from app import run_full_workflow
            result = run_full_workflow(profile, {"country": "中国", "city": city}, occasion)
            reply = self._format_outfit_reply(result)
            return {"intent": intent, "reply": reply, "data": result}

        elif intent == "weather_query":
            city = "上海"
            if profile and isinstance(profile, dict):
                city = profile.get("city", city)
            weather = self.weather_tool.get_weather(city)
            reply = (f"📍 {city}当前天气\n"
                     f"🌡️ 温度: {weather.get('temp', 20)}°C (体感{weather.get('feels_like', weather.get('temp', 20))}°C)\n"
                     f"🌤️ 天气: {weather.get('desc', '')}\n"
                     f"💧 湿度: {weather.get('humidity', 50)}%\n"
                     f"{weather.get('advice', '')}")
            return {"intent": intent, "reply": reply}

        elif intent == "cart_operation":
            conn = sqlite3.connect(self.db_path)
            conn.row_factory = sqlite3.Row
            rows = conn.execute("SELECT * FROM cart_items WHERE user_id = ?", (user_id,)).fetchall()
            conn.close()
            count = len(rows)
            total = sum(r["total_price"] for r in rows)
            if count == 0:
                reply = "🛒 您的购物车是空的，快去生成穿搭方案选购商品吧！"
            else:
                reply = f"🛒 您的购物车有 {count} 件商品，总计 ¥{round(total, 2)}。"
            return {"intent": intent, "reply": reply}

        else:
            return {"intent": "general", "reply":
                "我是AI智能穿搭顾问，可以帮您：\n"
                "1️⃣ 生成穿搭方案（发送\"搭配一套面试穿的衣服\"）\n"
                "2️⃣ 查询天气（发送\"今天天气怎么样\"）\n"
                "3️⃣ 管理购物车（发送\"看看购物车\"）\n\n"
                "请问您需要什么帮助？"}

    def _extract_occasion(self, text):
        occasions = ["面试", "约会", "婚礼", "派对", "旅行", "休闲", "日常通勤",
                      "商务会议", "商务出差", "颁奖典礼", "毕业典礼", "户外运动",
                      "朋友聚餐", "家庭聚会", "看展", "音乐会", "拍照写真",
                      "新年节日", "生日聚会"]
        for occ in occasions:
            if occ in text:
                return occ
        return "日常通勤"

    def _ensure_user(self, wx_user_id):
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        row = conn.execute(
            "SELECT id, user_id FROM wx_user_mapping WHERE wx_user_id = ?",
            (wx_user_id,)
        ).fetchone()
        if row:
            uid = row["user_id"]
            conn.close()
            return uid

        username = f"wx_{wx_user_id[:8]}"
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        cursor = conn.execute(
            "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
            (username, "wx_auto_login", now)
        )
        user_id = cursor.lastrowid
        conn.execute(
            "INSERT INTO wx_user_mapping (wx_user_id, user_id, created_at) VALUES (?, ?, ?)",
            (wx_user_id, user_id, now)
        )
        conn.commit()
        conn.close()
        return user_id

    def _format_outfit_reply(self, result):
        outfit = result.get("outfit", {})
        weather = result.get("weather", {})
        ps = result.get("profile_summary", {})
        products = result.get("products", {})

        lines = []
        lines.append("👔 为您生成专属穿搭方案")
        lines.append(f"📍 {ps.get('address', '')} | 🌡️ {weather.get('temp', 20)}°C {weather.get('desc', '')}")
        lines.append(f"🎯 场合: {outfit.get('occasion', '')} | 🗓️ {outfit.get('season', '')}")
        lines.append("")

        for item in outfit.get("items", []):
            lines.append(f"  {item.get('category', '')}: {item.get('name', '')}")
            lines.append(f"    颜色: {item.get('color', '')} | 面料: {item.get('material', '')}")
            lines.append(f"    {item.get('desc', '')}")
            lines.append("")

        lines.append(f"💡 {outfit.get('reason', '')}")
        lines.append("")

        best_picks = products.get("best_picks", [])
        if best_picks:
            lines.append("🔍 最优商品推荐:")
            for p in best_picks:
                lines.append(f"  [{p.get('platform', '')}] {p.get('product_name', '')}")
                lines.append(f"    💰 ¥{p.get('total_price', 0)} | ⭐ {p.get('rating', 0)} | {p.get('seller', '')}")
                if p.get("product_url"):
                    lines.append(f"    🔗 {p.get('product_url', '')}")
            lines.append("")
            lines.append(f"📦 推荐单品总计: ¥{products.get('total_price', 0)}")
            lines.append("")
            lines.append("👉 完整比价详情和商品图片，请访问网页端查看。")

        return "\n".join(lines)
