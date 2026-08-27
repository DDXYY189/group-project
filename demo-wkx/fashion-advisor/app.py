"""
AI 智能穿搭顾问 - Flask 后端 (Multi-Agent + Auth)
登录制系统：持久化账号、个人资料设置、地址驱动的穿搭咨询。
"""

import os
import json
import sqlite3
import hashlib
import secrets
import urllib.request
import urllib.parse
from datetime import datetime
from functools import wraps
from flask import (Flask, request, jsonify, render_template,
                   redirect, url_for, session, g, Response)

from agents import (
    IntentRouter, UserProfileAgent, WeatherTool,
    OutfitComposer, ProductSearcher, PriceComparator,
    CartManager, RiskCheckSkill, WeChatBotService,
)

app = Flask(__name__)
app.secret_key = secrets.token_hex(32)

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(_SCRIPT_DIR, "fashion_advisor.db")
try:
    _test = os.path.join(_SCRIPT_DIR, ".write_test")
    with open(_test, 'w') as _f:
        _f.write("ok")
    os.remove(_test)
except OSError:
    import tempfile
    DB_PATH = os.path.join(tempfile.gettempdir(), "fashion_advisor.db")

# ======================== Agent 实例化 ========================
intent_router = IntentRouter()
profile_agent = UserProfileAgent(DB_PATH)
weather_tool = WeatherTool()
outfit_composer = OutfitComposer()
product_searcher = ProductSearcher()
price_comparator = PriceComparator()
cart_manager = CartManager(DB_PATH)
risk_check = RiskCheckSkill()
wechat_bot = WeChatBotService(
    DB_PATH, profile_agent, weather_tool,
    outfit_composer, product_searcher, price_comparator,
    cart_manager, intent_router,
)

# ======================== 数据库初始化 ========================

REQUIRED_COLUMNS = {
    "user_id": "INTEGER",
    "birthday": "TEXT",
    "body_shape": "TEXT",
    "shoulder_width": "TEXT",
    "waist_position": "TEXT",
    "budget_min": "REAL",
    "budget_max": "REAL",
    "wardrobe_items": "TEXT DEFAULT '[]'",
    "taobao_account": "TEXT",
    "pinduoduo_account": "TEXT",
    "douyin_account": "TEXT",
    "vipshop_account": "TEXT",
}

def migrate_db(conn):
    cursor = conn.execute("PRAGMA table_info(user_profile)")
    existing = {row[1] for row in cursor.fetchall()}
    for col, col_type in REQUIRED_COLUMNS.items():
        if col not in existing:
            conn.execute(f"ALTER TABLE user_profile ADD COLUMN {col} {col_type}")

def init_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute('''
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            created_at TEXT NOT NULL
        )
    ''')
    conn.execute('''
        CREATE TABLE IF NOT EXISTS user_profile (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER,
            birthday TEXT,
            gender TEXT,
            height REAL,
            weight REAL,
            body_shape TEXT,
            shoulder_width TEXT,
            waist_position TEXT,
            clothing_size TEXT,
            shoe_size TEXT,
            color_preference TEXT,
            daily_style TEXT,
            preferred_fabric TEXT,
            lucky_color TEXT,
            budget_min REAL,
            budget_max REAL,
            wardrobe_items TEXT DEFAULT '[]',
            taobao_account TEXT,
            pinduoduo_account TEXT,
            douyin_account TEXT,
            vipshop_account TEXT
        )
    ''')
    migrate_db(conn)
    conn.execute('''
        CREATE TABLE IF NOT EXISTS cart_items (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER,
            platform TEXT NOT NULL,
            product_name TEXT NOT NULL,
            price REAL NOT NULL,
            shipping_fee REAL DEFAULT 0,
            total_price REAL NOT NULL,
            seller TEXT,
            product_url TEXT,
            sizes_available TEXT,
            selected_size TEXT,
            added_at TEXT NOT NULL,
            status TEXT DEFAULT 'added'
        )
    ''')
    conn.execute('''
        CREATE TABLE IF NOT EXISTS wx_user_mapping (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            wx_user_id TEXT UNIQUE NOT NULL,
            user_id INTEGER NOT NULL,
            created_at TEXT NOT NULL
        )
    ''')
    conn.commit()
    conn.close()

init_db()

# ======================== 认证工具 ========================

def hash_password(password):
    return hashlib.sha256(password.encode('utf-8')).hexdigest()

def login_required(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        if 'user_id' not in session:
            return redirect(url_for('login'))
        return f(*args, **kwargs)
    return decorated

def get_current_user():
    if 'user_id' in session:
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        row = conn.execute("SELECT * FROM users WHERE id = ?", (session['user_id'],)).fetchone()
        conn.close()
        return dict(row) if row else None
    return None

# ======================== 核心工作流 ========================

def run_full_workflow(profile, address_parts, occasion):
    """完整四步工作流：profile来自用户设置，address驱动天气"""
    weather = weather_tool.get_weather_by_address(address_parts)

    body_assessment = profile_agent.assess_body_type(
        profile.get("height"), profile.get("weight"), profile.get("body_shape")
    )

    outfit = outfit_composer.compose(profile, weather, occasion)

    all_products = []
    best_products = []
    for item in outfit.get("items", []):
        results = product_searcher.search(
            item,
            budget_min=profile.get("budget_min"),
            budget_max=profile.get("budget_max"),
        )
        comparison = price_comparator.compare(
            results,
            budget_min=profile.get("budget_min"),
            budget_max=profile.get("budget_max"),
        )
        best = comparison.get("best")
        if best:
            best["category"] = item.get("category", "")
            best_products.append(best)
        all_products.append({
            "item": item,
            "search_results": results,
            "comparison": comparison,
        })

    risk = risk_check.check(outfit, best_products, profile)
    total_best_price = sum(p.get("total_price", 0) for p in best_products)

    place = " ".join(v for v in address_parts.values() if v)
    return {
        "profile_summary": {
            "username": session.get("username", ""),
            "age": profile.get("age"),
            "gender": profile.get("gender", ""),
            "body_type": body_assessment.get("type", ""),
            "bmi": body_assessment.get("bmi", 0),
            "body_advice": body_assessment.get("advice", ""),
            "daily_style": profile.get("daily_style", "休闲"),
            "address": place,
        },
        "weather": weather,
        "outfit": outfit,
        "products": {
            "best_picks": best_products,
            "total_price": round(total_best_price, 2),
            "details": all_products,
        },
        "risk_check": risk,
        "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    }

# ======================== 路由：认证 ========================

@app.route('/register', methods=['GET', 'POST'])
def register():
    if request.method == 'POST':
        username = request.form.get('username', '').strip()
        password = request.form.get('password', '')
        if not username or not password:
            return render_template('login.html', error='用户名和密码不能为空', mode='register')
        if len(password) < 6:
            return render_template('login.html', error='密码至少6位', mode='register')
        import re
        if not re.search(r'[a-zA-Z]', password) or not re.search(r'\d', password):
            return render_template('login.html', error='密码必须同时包含字母和数字', mode='register')

        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        existing = conn.execute("SELECT id FROM users WHERE username = ?", (username,)).fetchone()
        if existing:
            conn.close()
            return render_template('login.html', error='用户名已存在', mode='register')

        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        cursor = conn.execute(
            "INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)",
            (username, hash_password(password), now)
        )
        user_id = cursor.lastrowid
        conn.commit()
        conn.close()

        session['user_id'] = user_id
        session['username'] = username
        return redirect(url_for('settings'))
    return render_template('login.html', mode='register')

@app.route('/login', methods=['GET', 'POST'])
def login():
    if request.method == 'POST':
        username = request.form.get('username', '').strip()
        password = request.form.get('password', '')
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        user = conn.execute(
            "SELECT * FROM users WHERE username = ? AND password_hash = ?",
            (username, hash_password(password))
        ).fetchone()
        conn.close()
        if user:
            session['user_id'] = user['id']
            session['username'] = user['username']
            return redirect(url_for('index'))
        return render_template('login.html', error='用户名或密码错误', mode='login')
    return render_template('login.html', mode='login')

@app.route('/logout')
def logout():
    session.clear()
    return redirect(url_for('login'))

# ======================== 路由：主页面 ========================

@app.route('/')
@login_required
def index():
    profile = profile_agent.get_profile(session['user_id'])
    return render_template('index.html', profile=profile, username=session.get('username'))

@app.route('/settings', methods=['GET', 'POST'])
@login_required
def settings():
    if request.method == 'POST':
        data = request.form.to_dict()
        try:
            profile_agent.save_profile(data, session['user_id'])
        except Exception as e:
            import traceback
            traceback.print_exc()
            return jsonify({"error": str(e)}), 500
        return redirect(url_for('index'))
    profile = profile_agent.get_profile(session['user_id'])
    return render_template('index.html', profile=profile, username=session.get('username'), active_tab='settings')

# ======================== 路由：穿搭咨询 ========================

@app.route('/consult', methods=['POST'])
@login_required
def consult():
    data = request.get_json(force=True)
    profile = profile_agent.get_profile(session['user_id'])
    if not profile:
        return jsonify({"error": "请先在设置中完善个人资料"}), 400

    address_parts = {
        "country": data.get("country", "中国"),
        "province": data.get("province", ""),
        "city": data.get("city", ""),
        "county": data.get("county", ""),
    }
    occasion = data.get("occasion", "日常通勤")

    result = run_full_workflow(profile, address_parts, occasion)
    return jsonify(result)

# ======================== 路由：购物车 ========================

@app.route('/cart')
@login_required
def view_cart():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        "SELECT * FROM cart_items WHERE user_id = ? ORDER BY added_at DESC",
        (session['user_id'],)
    ).fetchall()
    conn.close()
    items = []
    for row in rows:
        item = dict(row)
        sizes = item.get("sizes_available", "[]")
        try:
            item["sizes_available"] = json.loads(sizes)
        except:
            item["sizes_available"] = []
        items.append(item)
    total = sum(i["total_price"] for i in items)
    return jsonify({"items": items, "count": len(items), "total_price": round(total, 2)})

@app.route('/cart/add', methods=['POST'])
@login_required
def cart_add():
    data = request.get_json(force=True)
    items = data.get('items', [data]) if isinstance(data, dict) else data
    conn = sqlite3.connect(DB_PATH)
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    for item in items:
        sizes = item.get("sizes_available", [])
        if isinstance(sizes, list):
            sizes = json.dumps(sizes, ensure_ascii=False)
        conn.execute('''
            INSERT INTO cart_items
                (user_id, platform, product_name, price, shipping_fee, total_price,
                 seller, product_url, sizes_available, selected_size, added_at, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'added')
        ''', (
            session['user_id'],
            item.get("platform", ""), item.get("product_name", ""),
            item.get("price", 0), item.get("shipping_fee", 0),
            item.get("total_price", item.get("price", 0)),
            item.get("seller", ""), item.get("product_url", "#"),
            sizes, item.get("selected_size"), now,
        ))
    conn.commit()
    conn.close()
    total = sum(i.get("total_price", i.get("price", 0)) for i in items)
    return jsonify({
        "success": True, "count": len(items),
        "total_price": round(total, 2),
        "message": f"已将 {len(items)} 件商品加入购物车，总计 ¥{round(total, 2)}",
    })

@app.route('/cart/add_single', methods=['POST'])
@login_required
def cart_add_single():
    data = request.get_json(force=True)
    selected_size = data.pop('selected_size', None)
    sizes = data.get("sizes_available", [])
    if isinstance(sizes, list):
        sizes = json.dumps(sizes, ensure_ascii=False)
    conn = sqlite3.connect(DB_PATH)
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    conn.execute('''
        INSERT INTO cart_items
            (user_id, platform, product_name, price, shipping_fee, total_price,
             seller, product_url, sizes_available, selected_size, added_at, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'added')
    ''', (
        session['user_id'], data.get("platform", ""), data.get("product_name", ""),
        data.get("price", 0), data.get("shipping_fee", 0),
        data.get("total_price", data.get("price", 0)),
        data.get("seller", ""), data.get("product_url", "#"),
        sizes, selected_size, now,
    ))
    conn.commit()
    conn.close()
    return jsonify({"success": True, "message": f"已将【{data.get('product_name', '商品')}】加入购物车"})

@app.route('/cart/remove/<int:item_id>', methods=['POST'])
@login_required
def cart_remove(item_id):
    conn = sqlite3.connect(DB_PATH)
    conn.execute("DELETE FROM cart_items WHERE id = ? AND user_id = ?",
                 (item_id, session['user_id']))
    conn.commit()
    conn.close()
    return jsonify({"success": True, "message": "已移除"})

@app.route('/cart/clear', methods=['POST'])
@login_required
def cart_clear():
    conn = sqlite3.connect(DB_PATH)
    conn.execute("DELETE FROM cart_items WHERE user_id = ?", (session['user_id'],))
    conn.commit()
    conn.close()
    return jsonify({"success": True, "message": "购物车已清空"})

@app.route('/cart/checkout')
@login_required
def cart_checkout():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        "SELECT * FROM cart_items WHERE user_id = ? ORDER BY added_at DESC",
        (session['user_id'],)
    ).fetchall()
    conn.close()
    count = len(rows)
    total = sum(r["total_price"] for r in rows)
    if count == 0:
        return jsonify({"can_checkout": False, "message": "购物车为空"})
    return jsonify({
        "can_checkout": True,
        "message": f"购物车共 {count} 件商品，总计 ¥{round(total, 2)}",
        "total_price": round(total, 2),
        "warning": "请前往各平台App完成最终支付。本系统不代您执行支付操作。",
    })

# ======================== 路由：对话助手 ========================

@app.route('/chat', methods=['POST'])
@login_required
def chat():
    message = request.get_json(force=True).get('message', '')
    intent = intent_router.classify(message)
    profile = profile_agent.get_profile(session['user_id'])

    if intent == "outfit_request":
        if not profile:
            return jsonify({"reply": "请先在设置中完善个人资料，我才能为您生成穿搭方案。"})
        occasion = "日常通勤"
        for occ in ["面试", "约会", "婚礼", "派对", "旅行", "休闲", "商务会议", "商务出差", "颁奖典礼", "毕业典礼", "户外运动", "朋友聚餐", "家庭聚会", "看展", "音乐会", "拍照写真", "新年节日", "生日聚会"]:
            if occ in message:
                occasion = occ
                break
        # 使用用户默认城市
        city = profile.get("city", "上海")
        address_parts = {"country": "中国", "city": city}
        result = run_full_workflow(profile, address_parts, occasion)
        return jsonify({
            "intent": intent,
            "reply": f"已为您生成{occasion}穿搭方案！",
            "data": result,
        })

    elif intent == "weather_query":
        city = profile.get("city", "上海") if profile else "上海"
        weather = weather_tool.get_weather(city)
        return jsonify({
            "intent": intent,
            "reply": f"{city}当前天气: {weather['desc']}, {weather['temp']}°C (体感{weather.get('feels_like', weather['temp'])}°C)。\n{weather.get('advice', '')}",
        })

    elif intent == "cart_operation":
        conn = sqlite3.connect(DB_PATH)
        conn.row_factory = sqlite3.Row
        rows = conn.execute("SELECT * FROM cart_items WHERE user_id = ?", (session['user_id'],)).fetchall()
        conn.close()
        count = len(rows)
        total = sum(r["total_price"] for r in rows)
        return jsonify({
            "intent": intent,
            "reply": f"您的购物车有 {count} 件商品，总计 ¥{round(total, 2)}。",
        })

    else:
        return jsonify({
            "intent": "general",
            "reply": "我是AI智能穿搭顾问，可以帮您: 1) 生成穿搭方案 2) 多平台比价 3) 管理购物车。请问您需要什么帮助？",
        })

# ======================== 微信机器人控制台 ========================

@app.route('/dashboard')
def dashboard():
    return render_template('console.html')

@app.route('/api/bot/status')
def bot_status():
    local_status = wechat_bot.get_status()
    try:
        import urllib.request as _urllib
        req = _urllib.Request("http://localhost:8080/api/bot/status")
        with _urllib.urlopen(req, timeout=3) as resp:
            java_status = json.loads(resp.read().decode())
        return jsonify({
            "loggedIn": java_status.get("loggedIn", False),
            "connectionStatus": java_status.get("connectionStatus", "DISCONNECTED"),
            "llmConfigured": java_status.get("llmConfigured", True),
            "tools": java_status.get("tools", local_status["tools"]),
            "loginError": java_status.get("loginError"),
            "botId": java_status.get("botId", ""),
            "userId": java_status.get("userId", ""),
            "javaBot": True,
        })
    except Exception:
        return jsonify(local_status)

@app.route('/api/bot/qr.png')
def bot_qr():
    try:
        import urllib.request as _urllib
        req = _urllib.Request("http://localhost:8080/api/bot/qr.png")
        with _urllib.urlopen(req, timeout=3) as resp:
            png = resp.read()
        return Response(png, content_type='image/png')
    except Exception:
        png = wechat_bot.generate_qr_png()
        return Response(png, content_type='image/png')

@app.route('/api/bot/relogin', methods=['POST'])
def bot_relogin():
    wechat_bot.trigger_relogin()
    return jsonify({"success": True, "message": "已重新生成二维码"})

@app.route('/api/bot/scan', methods=['POST'])
def bot_scan():
    try:
        import urllib.request as _urllib
        req = _urllib.Request("http://localhost:8080/api/bot/status")
        with _urllib.urlopen(req, timeout=3) as resp:
            java_status = json.loads(resp.read().decode())
        if java_status.get("loggedIn"):
            return jsonify({"success": True, "message": "微信已登录", "loggedIn": True, "botId": java_status.get("botId", "")})
        return jsonify({"success": False, "message": "请用微信扫描二维码登录", "loggedIn": False})
    except Exception:
        wechat_bot.simulate_login()
        return jsonify({"success": True, "message": "模拟扫码登录成功（本地模式）"})

@app.route('/api/bot/memory/clear', methods=['POST'])
def bot_memory_clear():
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.execute("DELETE FROM wx_user_mapping")
    deleted = cursor.rowcount
    conn.commit()
    conn.close()
    return jsonify({"success": True, "deleted": deleted})

@app.route('/api/bot/message', methods=['POST'])
def bot_message():
    data = request.get_json(force=True)
    wx_user_id = data.get('wx_user_id', 'console_test')
    text = data.get('text', '')
    result = wechat_bot.process_message(wx_user_id, text)
    return jsonify(result)

@app.route('/api/bot/rich_message', methods=['POST'])
def bot_rich_message():
    """富数据消息接口 - 返回结构化穿搭方案+商品图片+比价结果"""
    data = request.get_json(force=True)
    text = data.get('text', '')

    # 优先使用已登录用户的画像
    user_id = session.get('user_id')
    if user_id:
        profile = profile_agent.get_profile(user_id)
        intent = intent_router.classify(text)

        if intent == "outfit_request" and profile:
            occasion = "日常通勤"
            for occ in ["面试","约会","婚礼","派对","旅行","休闲","日常通勤","商务会议",
                        "商务出差","颁奖典礼","毕业典礼","户外运动","朋友聚餐","家庭聚会",
                        "看展","音乐会","拍照写真","新年节日","生日聚会","其他"]:
                if occ in text:
                    occasion = occ
                    break
            city = profile.get("city", "上海")
            address_parts = {"country": "中国", "city": city}
            result = run_full_workflow(profile, address_parts, occasion)
            reply = wechat_bot._format_outfit_reply(result)
            return jsonify({"intent": intent, "reply": reply, "data": result, "user_id": user_id})

        elif intent == "weather_query":
            city = profile.get("city", "上海")
            weather = weather_tool.get_weather(city)
            return jsonify({"intent": intent, "reply": f"{city}当前天气: {weather['desc']}, {weather['temp']}°C", "data": weather})

        elif intent == "cart_operation":
            conn = sqlite3.connect(DB_PATH)
            conn.row_factory = sqlite3.Row
            rows = conn.execute("SELECT * FROM cart_items WHERE user_id = ?", (user_id,)).fetchall()
            conn.close()
            items = [dict(r) for r in rows]
            total = sum(r["total_price"] for r in rows)
            return jsonify({"intent": intent, "reply": f"购物车有 {len(items)} 件商品，总计 ¥{round(total, 2)}", "data": {"items": items, "total": round(total, 2)}})

    # 未登录时回退到 WeChatBot
    wx_user_id = data.get('wx_user_id', 'console_test')
    result = wechat_bot.process_message(wx_user_id, text)
    return jsonify(result)

# ======================== 模拟数据接口（测试用） ========================

import random as _mock_random

_MOCK_PLATFORMS = ["淘宝", "拼多多", "抖音", "唯品会"]
_MOCK_PLATFORM_ICONS = {"淘宝": "\U0001f351", "拼多多": "\u2728", "抖音": "\U0001f3a5", "唯品会": "\U0001f4b0"}
_MOCK_SELLER_PREFIX = ["优品", "精选", "潮流", "风尚", "品质", "优选", "旗舰", "直营"]
_MOCK_SELLER_SUFFIX = ["旗舰店", "专卖店", "直供店", "工厂店", "专营店"]

def _gen_mock_product(item_name, category, color, material):
    """生成单个模拟商品"""
    platform = _mock_random.choice(_MOCK_PLATFORMS)
    base_price = {"上装": 150, "下装": 120, "外套": 300, "鞋履": 200, "配饰": 80}.get(category, 150)
    material_mult = 1.0
    if "羊绒" in material: material_mult = 2.5
    elif "羊毛" in material: material_mult = 1.8
    elif "丝" in material: material_mult = 2.0
    elif "棉" in material: material_mult = 1.0
    price = round(base_price * material_mult * _mock_random.uniform(0.6, 1.3), 2)
    original_price = round(price * _mock_random.uniform(1.15, 1.5), 2)
    shipping = 0 if platform in ("拼多多",) or (platform == "淘宝" and price >= 88) else _mock_random.choice([0, 8, 10])
    total_price = round(price + shipping, 2)
    seller = f"{_mock_random.choice(_MOCK_SELLER_PREFIX)}{_mock_random.choice(_MOCK_SELLER_SUFFIX)}"
    suffixes = ["旗舰款", "热销款", "新品", "爆款", "精选", "专供"]
    prefix_list = ["2024新款", "品牌", "直供", "官方", "工厂"]
    name_parts = []
    if _mock_random.random() > 0.5:
        name_parts.append(_mock_random.choice(prefix_list))
    if color:
        name_parts.append(color)
    name_parts.append(item_name)
    if material:
        name_parts.append(f"({material})")
    name_parts.append(_mock_random.choice(suffixes))
    product_name = "".join(name_parts)
    query = f"{color} {material} {item_name}".strip()
    encoded_q = urllib.parse.quote(query)
    url_map = {
        "淘宝": f"https://s.taobao.com/search?q={encoded_q}",
        "拼多多": f"https://mobile.yangkeduo.com/search_result.html?search_key={encoded_q}",
        "抖音": f"https://www.douyin.com/search/{encoded_q}",
        "唯品会": f"https://category.vip.com/s/search.php?q={encoded_q}",
    }
    sizes = {"鞋履": ["39","40","41","42","43"], "上装": ["S","M","L","XL"], "下装": ["28","30","32","34"]}.get(category, ["均码"])
    return {
        "platform": platform,
        "platform_icon": _MOCK_PLATFORM_ICONS.get(platform, "\u2728"),
        "product_name": product_name,
        "price": price,
        "shipping_fee": shipping,
        "total_price": total_price,
        "original_price": original_price,
        "discount": round((1 - price / original_price) * 100, 1),
        "rating": round(_mock_random.uniform(4.2, 4.9), 1),
        "sales": _mock_random.randint(100, 50000),
        "seller": seller,
        "product_url": url_map.get(platform, url_map["淘宝"]),
        "in_stock": True,
        "sizes_available": sizes,
        "score": round(_mock_random.uniform(60, 95), 2),
        "source": "mock",
    }

def _gen_mock_data(occasion="日常通勤"):
    """生成完整的模拟测试数据"""
    seasons = ["春季", "夏季", "秋季", "冬季"]
    season = _mock_random.choice(seasons)

    outfit_items = [
        {"category": "上装", "name": "纯棉白衬衫", "color": "白色", "material": "棉", "desc": "经典修身版型，适合多场合穿着"},
        {"category": "下装", "name": "直筒西裤", "color": "深灰色", "material": "涤纶混纺", "desc": "高腰设计，拉长腿部线条"},
        {"category": "外套", "name": "轻薄风衣", "color": "卡其色", "material": "棉", "desc": "防风透气，春秋必备单品"},
        {"category": "鞋履", "name": "皮质乐福鞋", "color": "棕色", "material": "牛皮", "desc": "舒适百搭，商务休闲皆宜"},
        {"category": "配饰", "name": "简约皮带", "color": "棕色", "material": "牛皮", "desc": "提升整体穿搭质感"},
    ]

    reasons = [
        "根据当前气温和您的体型分析，推荐轻薄叠穿方案，兼顾保暖与时尚感。配色以中性色为主，适合多种场合。",
        "结合天气状况和您的个人风格偏好，选择经典商务休闲搭配，突出干练气质同时保持舒适度。",
        "依据今日天气和体型特征，推荐层次感穿搭，通过材质对比营造视觉层次，适配温差变化。",
    ]

    all_details = []
    best_picks = []
    for item in outfit_items:
        products = [_gen_mock_product(item["name"], item["category"], item["color"], item["material"]) for _ in range(4)]
        products.sort(key=lambda x: x["score"], reverse=True)
        best = products[0]
        best["recommend_reason"] = f"综合性价比最高，{best['platform']}平台{best['seller']}售价¥{best['total_price']}，评分{best['rating']}⭐"
        all_details.append({"item": item, "comparison": {"comparison": products, "best": best}})
        best_picks.append(best)

    total_price = round(sum(p["total_price"] for p in best_picks), 2)

    return {
        "profile_summary": {
            "username": "测试用户",
            "age": 25,
            "gender": "男",
            "body_type": "标准体型",
            "bmi": 22.1,
            "body_advice": "体型匀称，适合大多数风格，建议选择修身版型突出线条感。",
            "daily_style": "商务休闲",
            "address": "上海市浦东新区",
        },
        "weather": {
            "temp": 18,
            "feels_like": 16,
            "desc": "多云转晴",
            "humidity": 55,
            "wind_speed": 3.2,
            "advice": "气温适中，建议外搭薄外套，早晚注意保暖。",
        },
        "outfit": {
            "season": season,
            "occasion": occasion,
            "weather_advice": "温差较大，建议采用洋葱式穿搭法，方便增减衣物。",
            "items": outfit_items,
            "reason": _mock_random.choice(reasons),
        },
        "products": {
            "best_picks": best_picks,
            "total_price": total_price,
            "details": all_details,
        },
        "risk_check": {
            "warnings": [],
            "recommendations": [
                "建议查看商品尺码表，确认合适尺寸后再下单",
                "部分平台商品可能存在色差，建议参考买家秀",
            ],
        },
        "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    }

@app.route('/api/mock')
def api_mock():
    """模拟数据接口 - 无需登录，返回完整的穿搭推荐测试数据"""
    occasion = request.args.get('occasion', '日常通勤')
    return jsonify(_gen_mock_data(occasion))

@app.route('/api/mock/products')
def api_mock_products():
    """仅返回模拟商品比价数据"""
    occasion = request.args.get('occasion', '日常通勤')
    data = _gen_mock_data(occasion)
    return jsonify(data["products"])

@app.route('/api/mock/outfit')
def api_mock_outfit():
    """仅返回模拟穿搭方案数据"""
    occasion = request.args.get('occasion', '日常通勤')
    data = _gen_mock_data(occasion)
    return jsonify({
        "outfit": data["outfit"],
        "weather": data["weather"],
        "profile_summary": data["profile_summary"],
    })

@app.route('/test')
def test_page():
    """测试页面 - 无需登录，直接渲染模拟数据"""
    return render_template('test.html')

# ======================== 启动 ========================


if __name__ == '__main__':
    print(f"\033[92m🚀 AI 智能穿搭顾问已启动: http://127.0.0.1:5000 \033[0m")
    app.run(debug=True, host='0.0.0.0', port=5000, threaded=True)
