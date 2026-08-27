import sqlite3
import json
from datetime import datetime, date

class UserProfileAgent:
    def __init__(self, db_path):
        self.db_path = db_path

    def _get_db(self):
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def get_profile(self, user_id=None):
        conn = self._get_db()
        if user_id:
            row = conn.execute(
                "SELECT * FROM user_profile WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                (user_id,)
            ).fetchone()
        else:
            row = conn.execute(
                "SELECT * FROM user_profile ORDER BY id DESC LIMIT 1"
            ).fetchone()
        conn.close()
        if row:
            profile = dict(row)
            profile["wardrobe_items"] = json.loads(profile.get("wardrobe_items", "[]"))
            # 从出生日期自动计算年龄
            if profile.get("birthday"):
                profile["age"] = self._calc_age(profile["birthday"])
            return profile
        return None

    def save_profile(self, data, user_id=None):
        conn = self._get_db()
        wardrobe = data.get("wardrobe_items", "[]")
        if isinstance(wardrobe, list):
            wardrobe = json.dumps(wardrobe, ensure_ascii=False)

        if user_id:
            existing = conn.execute(
                "SELECT id FROM user_profile WHERE user_id = ? LIMIT 1", (user_id,)
            ).fetchone()
            if existing:
                conn.execute("""
                    UPDATE user_profile SET
                        birthday=?, gender=?, height=?, weight=?, body_shape=?,
                        shoulder_width=?, waist_position=?, clothing_size=?, shoe_size=?,
                        color_preference=?, daily_style=?, preferred_fabric=?,
                        lucky_color=?, budget_min=?, budget_max=?, wardrobe_items=?,
                        taobao_account=?, pinduoduo_account=?, douyin_account=?, vipshop_account=?
                    WHERE user_id=?
                """, (
                    data.get("birthday"), data.get("gender"),
                    data.get("height"), data.get("weight"),
                    data.get("body_shape"), data.get("shoulder_width"),
                    data.get("waist_position"), data.get("clothing_size"),
                    data.get("shoe_size"), data.get("color_preference"),
                    data.get("daily_style"), data.get("preferred_fabric"),
                    data.get("lucky_color"), data.get("budget_min"),
                    data.get("budget_max"), wardrobe,
                    data.get("taobao_account"), data.get("pinduoduo_account"),
                    data.get("douyin_account"), data.get("vipshop_account"),
                    user_id
                ))
                conn.commit()
                conn.close()
                return

        conn.execute("""
            INSERT INTO user_profile
                (user_id, birthday, gender, height, weight, body_shape,
                 shoulder_width, waist_position, clothing_size, shoe_size,
                 color_preference, daily_style, preferred_fabric, lucky_color,
                 budget_min, budget_max, wardrobe_items,
                 taobao_account, pinduoduo_account, douyin_account, vipshop_account)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """, (
            user_id, data.get("birthday"), data.get("gender"),
            data.get("height"), data.get("weight"),
            data.get("body_shape"), data.get("shoulder_width"),
            data.get("waist_position"), data.get("clothing_size"),
            data.get("shoe_size"), data.get("color_preference"),
            data.get("daily_style"), data.get("preferred_fabric"),
            data.get("lucky_color"), data.get("budget_min"),
            data.get("budget_max"), wardrobe,
            data.get("taobao_account"), data.get("pinduoduo_account"),
            data.get("douyin_account"), data.get("vipshop_account")
        ))
        conn.commit()
        conn.close()

    def _calc_age(self, birthday_str):
        """从出生日期自动计算年龄，随时间推移自动更新"""
        try:
            birth = datetime.strptime(birthday_str, "%Y-%m-%d").date()
            today = date.today()
            age = today.year - birth.year
            if today.month < birth.month or (today.month == birth.month and today.day < birth.day):
                age -= 1
            return age
        except:
            return None

    def assess_body_type(self, height, weight, body_shape=None):
        if not height or not weight:
            return {"type": "unknown", "advice": "请补充身高体重信息"}
        h = float(height) / 100
        w = float(weight)
        bmi = w / (h * h) if h > 0 else 0
        if bmi < 18.5:
            t = "偏瘦"; advice = "建议选择有版型的衣物增加体量感，浅色系、横条纹"
        elif bmi < 24:
            t = "标准"; advice = "标准体型，大多数版型都适合，可按风格自由选择"
        elif bmi < 28:
            t = "微胖"; advice = "建议深色系、竖条纹，避免过于宽松的版型，合身剪裁更佳"
        else:
            t = "偏胖"; advice = "建议深色系、V领上衣，直筒裤，避免高领和横条纹"
        if body_shape:
            if "宽肩" in body_shape:
                advice += "；肩宽可选V领、插肩袖，避免垫肩"
            elif "窄肩" in body_shape:
                advice += "；窄肩可选垫肩、落肩袖，增加肩部宽度"
            if "腰低" in body_shape or "长腿" in body_shape:
                advice += "；高腰裤可优化比例"
        return {"type": t, "bmi": round(bmi, 1), "advice": advice}
