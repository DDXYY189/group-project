"""
AI 智能穿搭顾问 - Flask 后端
功能：用户画像存储 + 情景输入 + 智能搜索 + 穿搭推荐
"""

import os
import json
import sqlite3
from datetime import datetime
from flask import Flask, request, jsonify, render_template, redirect, url_for

app = Flask(__name__)

# ======================== 搜索 API 配置 ========================
# 填入有效 Key 后自动切换为真实搜索模式（SerpAPI / Brave Search）
SERPAPI_KEY = "your_key"

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(_SCRIPT_DIR, "fashion_advisor.db")
# 若脚本目录不可写（沙箱环境），回退到系统临时目录
try:
    _test = os.path.join(_SCRIPT_DIR, ".write_test")
    with open(_test, 'w') as _f:
        _f.write("ok")
    os.remove(_test)
except OSError:
    import tempfile
    DB_PATH = os.path.join(tempfile.gettempdir(), "fashion_advisor.db")


# ======================== 数据库操作 ========================

def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    conn = get_db()
    conn.execute('''
        CREATE TABLE IF NOT EXISTS user_profile (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            age INTEGER,
            height REAL,
            weight REAL,
            clothing_size TEXT,
            shoe_size TEXT,
            city TEXT,
            color_preference TEXT,
            daily_style TEXT,
            preferred_fabric TEXT,
            lucky_color TEXT
        )
    ''')
    conn.commit()
    conn.close()


def get_user_profile():
    conn = get_db()
    row = conn.execute('SELECT * FROM user_profile ORDER BY id DESC LIMIT 1').fetchone()
    conn.close()
    return dict(row) if row else None


def save_user_profile(data):
    conn = get_db()
    conn.execute('''
        INSERT INTO user_profile
            (age, height, weight, clothing_size, shoe_size, city,
             color_preference, daily_style, preferred_fabric, lucky_color)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ''', (
        data.get('age'), data.get('height'), data.get('weight'),
        data.get('clothing_size'), data.get('shoe_size'), data.get('city'),
        data.get('color_preference'), data.get('daily_style'),
        data.get('preferred_fabric'), data.get('lucky_color')
    ))
    conn.commit()
    conn.close()


# ======================== 辅助函数 ========================

def get_season(date_str):
    try:
        dt = datetime.strptime(date_str[:10], '%Y-%m-%d')
        m = dt.month
        if m in (3, 4, 5): return '春季'
        if m in (6, 7, 8): return '夏季'
        if m in (9, 10, 11): return '秋季'
        return '冬季'
    except Exception:
        return '秋季'


def build_search_query(profile, scenario):
    season = get_season(scenario.get('date', ''))
    location = scenario.get('location', '')
    occasion = scenario.get('occasion', '')
    style = profile.get('daily_style', '休闲') if profile else '休闲'
    age = profile.get('age', 25) if profile else 25
    gender = '男士' if any(k in style for k in ['商务', '运动', '工装']) else '女士'
    year = datetime.now().year
    query = f"{year} {season} {location} {occasion} {gender} {style} 穿搭"
    return query


# ======================== Mock 推荐数据 ========================

MOCK_OUTFITS = {
    "春季": {
        "婚礼": {
            "top": {"category": "上装", "name": "浅粉色修身西装外套", "color": "浅粉色", "material": "羊毛混纺", "description": "春季婚礼首选，浅粉色温暖而不失正式感，修身剪裁凸显气质"},
            "bottom": {"category": "下装", "name": "米白色高腰西裤", "color": "米白色", "material": "精纺羊毛", "description": "高腰修身版型，与浅色上装搭配和谐统一，拉长腿部线条"},
            "shoes": {"category": "鞋子", "name": "香槟色丝绒乐福鞋", "color": "香槟色", "material": "丝绒", "description": "丝绒材质呼应季节质感，乐福鞋型兼顾舒适与正式"},
            "reason": "春季婚礼以浅色系为主，浅粉+米白的搭配温柔优雅，香槟色丝绒鞋增添整体精致感，适合户外或室内仪式。"
        },
        "商务会议": {
            "top": {"category": "上装", "name": "藏青色单排扣西装", "color": "藏青色", "material": "羊毛", "description": "经典商务色系，面料挺括有型，适合正式会议场合"},
            "bottom": {"category": "下装", "name": "灰色修身西裤", "color": "灰色", "material": "羊毛混纺", "description": "灰色与藏青形成层次，修身但不紧绷"},
            "shoes": {"category": "鞋子", "name": "黑色牛皮德比鞋", "color": "黑色", "material": "牛皮", "description": "经典德比鞋款，百搭且正式"},
            "reason": "春季商务会议推荐藏青+灰色经典商务搭配，稳重专业又不显沉闷，适合写字楼办公场景。"
        },
        "约会": {
            "top": {"category": "上装", "name": "奶白色针织Polo衫", "color": "奶白色", "material": "棉质针织", "description": "柔软针织面料，Polo领设计休闲中带精致感"},
            "bottom": {"category": "下装", "name": "卡其色修身休闲裤", "color": "卡其色", "material": "棉", "description": "修身休闲版型，百搭卡其色"},
            "shoes": {"category": "鞋子", "name": "白色帆布板鞋", "color": "白色", "material": "帆布", "description": "清新白色，适合春季约会氛围"},
            "reason": "春季约会推荐柔和色调搭配，奶白+卡其清爽自然，白色板鞋增添年轻活力感。"
        },
        "日常": {
            "top": {"category": "上装", "name": "薄荷绿圆领T恤", "color": "薄荷绿", "material": "纯棉", "description": "春季清新色系，纯棉透气舒适"},
            "bottom": {"category": "下装", "name": "浅蓝色直筒牛仔裤", "color": "浅蓝色", "material": "弹力牛仔", "description": "经典直筒版型，浅蓝色适合春季"},
            "shoes": {"category": "鞋子", "name": "米色帆布鞋", "color": "米色", "material": "帆布", "description": "百搭米色，日常出行首选"},
            "reason": "春季日常以舒适清新为主，薄荷绿+浅蓝的自然色调让人心情愉悦，米色帆布鞋轻松百搭。"
        }
    },
    "夏季": {
        "婚礼": {
            "top": {"category": "上装", "name": "白色亚麻衬衫", "color": "白色", "material": "亚麻", "description": "透气亚麻面料，白色清爽正式，夏季婚礼经典之选"},
            "bottom": {"category": "下装", "name": "浅灰色西装短裤", "color": "浅灰色", "material": "棉混纺", "description": "修身短裤版型，兼顾正式与清凉"},
            "shoes": {"category": "鞋子", "name": "棕色编织皮带凉鞋", "color": "棕色", "material": "牛皮", "description": "编织设计精致有型，皮凉鞋正式感适中"},
            "reason": "夏季婚礼推荐白色亚麻衬衫，透气有质感；浅灰短裤清凉不失正式；棕色皮凉鞋增添度假风情。"
        },
        "商务会议": {
            "top": {"category": "上装", "name": "天蓝色短袖衬衫", "color": "天蓝色", "material": "高支棉", "description": "高支棉透气挺括，天蓝色清爽专业"},
            "bottom": {"category": "下装", "name": "深灰色修身西裤", "color": "深灰色", "material": "轻薄羊毛", "description": "轻薄面料适合夏季，深色稳重"},
            "shoes": {"category": "鞋子", "name": "黑色乐福鞋", "color": "黑色", "material": "牛皮", "description": "无系带设计，穿脱方便且正式"},
            "reason": "夏季商务会议推荐天蓝短袖+深灰西裤，清爽专业的配色方案，黑色乐福鞋兼顾空调房与通勤。"
        },
        "约会": {
            "top": {"category": "上装", "name": "珊瑚粉短袖衬衫", "color": "珊瑚粉", "material": "棉麻混纺", "description": "珊瑚粉浪漫温柔，棉麻面料透气舒适"},
            "bottom": {"category": "下装", "name": "白色修身休闲裤", "color": "白色", "material": "棉", "description": "白色裤装清爽时尚，修身版型"},
            "shoes": {"category": "鞋子", "name": "米色麂皮乐福鞋", "color": "米色", "material": "麂皮", "description": "米色温柔百搭，麂皮质感精致"},
            "reason": "夏季约会推荐珊瑚粉+白色的浪漫搭配，清爽有活力，米色麂皮乐福鞋增添成熟魅力。"
        },
        "日常": {
            "top": {"category": "上装", "name": "白色基础款T恤", "color": "白色", "material": "纯棉", "description": "夏季必备白色T恤，百搭透气"},
            "bottom": {"category": "下装", "name": "藏蓝色运动短裤", "color": "藏蓝色", "material": "速干面料", "description": "速干面料清凉舒适，运动风"},
            "shoes": {"category": "鞋子", "name": "白色运动凉鞋", "color": "白色", "material": "EVA", "description": "轻便凉爽，适合夏季日常"},
            "reason": "夏季日常以清凉为主，白色T恤+藏蓝短裤简单舒适，白色运动凉鞋轻便透气。"
        }
    },
    "秋季": {
        "婚礼": {
            "top": {"category": "上装", "name": "深蓝色丝绒西装外套", "color": "深蓝色", "material": "丝绒", "description": "光泽感丝绒面料，修身剪裁，婚礼正式场合的理想选择"},
            "bottom": {"category": "下装", "name": "黑色高腰修身西裤", "color": "黑色", "material": "羊毛", "description": "修身高腰版型，拉长腿部线条，与深蓝上装形成层次"},
            "shoes": {"category": "鞋子", "name": "棕色牛皮牛津鞋", "color": "棕色", "material": "牛皮", "description": "经典牛津鞋款，与西装完美搭配，棕色增添暖意"},
            "reason": "秋季婚礼以深色系为主，深蓝丝绒+黑色西裤的组合既正式又有质感，棕色牛津鞋增添一丝温暖色调，整体搭配沉稳大气。"
        },
        "商务会议": {
            "top": {"category": "上装", "name": "炭灰色双排扣西装", "color": "炭灰色", "material": "羊毛", "description": "双排扣设计气场十足，炭灰色沉稳专业"},
            "bottom": {"category": "下装", "name": "黑色修身西裤", "color": "黑色", "material": "羊毛", "description": "黑色百搭稳重，修身版型利落"},
            "shoes": {"category": "鞋子", "name": "黑色亮面牛津鞋", "color": "黑色", "material": "牛皮", "description": "亮面设计增添正式感"},
            "reason": "秋季商务推荐炭灰双排扣西装+黑色西裤，气场强大专业感强，亮面牛津鞋提升整体精致度。"
        },
        "约会": {
            "top": {"category": "上装", "name": "焦糖色圆领毛衣", "color": "焦糖色", "material": "羊绒混纺", "description": "焦糖色温暖浪漫，羊绒混纺柔软亲肤"},
            "bottom": {"category": "下装", "name": "深棕色灯芯绒裤", "color": "深棕色", "material": "灯芯绒", "description": "灯芯绒面料复古有质感，深棕色秋意浓"},
            "shoes": {"category": "鞋子", "name": "棕色麂皮切尔西靴", "color": "棕色", "material": "麂皮", "description": "切尔西靴型时尚百搭，麂皮质感佳"},
            "reason": "秋季约会推荐焦糖色毛衣+灯芯绒裤，暖色调营造温馨氛围，切尔西靴增添时尚感，适合餐厅或公园约会。"
        },
        "日常": {
            "top": {"category": "上装", "name": "燕麦色连帽卫衣", "color": "燕麦色", "material": "棉混纺", "description": "燕麦色温柔百搭，连帽设计休闲舒适"},
            "bottom": {"category": "下装", "name": "黑色直筒运动裤", "color": "黑色", "material": "棉", "description": "直筒运动裤舒适宽松，黑色百搭"},
            "shoes": {"category": "鞋子", "name": "白色老爹运动鞋", "color": "白色", "material": "网布+橡胶", "description": "老爹鞋型潮流舒适，白色百搭"},
            "reason": "秋季日常以舒适休闲为主，燕麦色卫衣+黑色运动裤轻松自在，白色老爹鞋增添潮流感。"
        }
    },
    "冬季": {
        "婚礼": {
            "top": {"category": "上装", "name": "酒红色粗花呢西装", "color": "酒红色", "material": "粗花呢", "description": "酒红色喜庆典雅，粗花呢面料有质感且保暖"},
            "bottom": {"category": "下装", "name": "黑色羊毛西裤", "color": "黑色", "material": "羊毛", "description": "保暖羊毛面料，黑色百搭正式"},
            "shoes": {"category": "鞋子", "name": "黑色牛皮切尔西靴", "color": "黑色", "material": "牛皮", "description": "切尔西靴保暖有型，正式场合适配"},
            "reason": "冬季婚礼推荐酒红粗花呢西装，喜庆且保暖；黑色羊毛西裤+切尔西靴，整体搭配庄重而温暖。"
        },
        "商务会议": {
            "top": {"category": "上装", "name": "深灰色羊绒大衣", "color": "深灰色", "material": "羊绒", "description": "羊绒大衣保暖有型，深灰色专业沉稳"},
            "bottom": {"category": "下装", "name": "黑色加厚西裤", "color": "黑色", "material": "羊毛", "description": "加厚保暖，修身版型"},
            "shoes": {"category": "鞋子", "name": "黑色牛皮靴", "color": "黑色", "material": "牛皮", "description": "保暖皮靴，正式且实用"},
            "reason": "冬季商务推荐深灰羊绒大衣+黑色加厚西裤，保暖的同时保持专业形象，黑色皮靴兼顾正式与御寒。"
        },
        "约会": {
            "top": {"category": "上装", "name": "奶咖色高领毛衣", "color": "奶咖色", "material": "羊绒", "description": "高领设计保暖优雅，奶咖色温柔高级"},
            "bottom": {"category": "下装", "name": "深灰色羊毛阔腿裤", "color": "深灰色", "material": "羊毛", "description": "阔腿裤时尚有型，羊毛保暖"},
            "shoes": {"category": "鞋子", "name": "棕色短靴", "color": "棕色", "material": "牛皮", "description": "短靴保暖时尚，棕色温暖"},
            "reason": "冬季约会推荐奶咖色高领毛衣+深灰阔腿裤，温柔高级的暖色调搭配，棕色短靴增添冬日氛围感。"
        },
        "日常": {
            "top": {"category": "上装", "name": "黑色羽绒服", "color": "黑色", "material": "涤纶+白鹅绒", "description": "轻便保暖，黑色百搭实用"},
            "bottom": {"category": "下装", "name": "深蓝色加绒牛仔裤", "color": "深蓝色", "material": "加绒牛仔", "description": "加绒保暖，深蓝经典百搭"},
            "shoes": {"category": "鞋子", "name": "黑色加绒雪地靴", "color": "黑色", "material": "人造毛+橡胶", "description": "加绒保暖，防滑鞋底"},
            "reason": "冬季日常以保暖为重，黑色羽绒服+加绒牛仔裤实用百搭，加绒雪地靴确保足部温暖防滑。"
        }
    }
}

DEFAULT_OUTFIT = {
    "top": {"category": "上装", "name": "简约纯色衬衫", "color": "白色", "material": "棉", "description": "经典百搭单品，适合多种场合"},
    "bottom": {"category": "下装", "name": "修身休闲裤", "color": "深灰色", "material": "棉混纺", "description": "修身版型，百搭深灰色"},
    "shoes": {"category": "鞋子", "name": "休闲运动鞋", "color": "白色", "material": "网布+橡胶", "description": "舒适百搭运动鞋"},
    "reason": "根据您的画像和情景，推荐简约百搭的穿搭方案，适合多种场合。"
}


def personalize_outfit(outfit, profile):
    """根据用户画像个性化推荐"""
    if not profile:
        return outfit
    items = [outfit["top"], outfit["bottom"], outfit["shoes"]]
    # 融入颜色偏好
    pref_color = profile.get("color_preference", "")
    if pref_color:
        items[0]["color"] = pref_color
        items[0]["description"] += f"（融入您偏爱的{pref_color}色调）"
    # 融入幸运色到鞋子
    lucky = profile.get("lucky_color", "")
    if lucky:
        items[2]["color"] = lucky
        items[2]["description"] += f"（点缀幸运色{lucky}）"
    # 融入面料偏好
    fabric = profile.get("preferred_fabric", "")
    if fabric:
        for it in items:
            if fabric in it["material"] or it["material"] in fabric:
                it["description"] += f"（含您偏好的{fabric}面料）"
    # 尺码信息
    csize = profile.get("clothing_size", "")
    ssize = profile.get("shoe_size", "")
    if csize:
        items[0]["description"] += f" | 建议尺码：{csize}"
    if ssize:
        items[2]["description"] += f" | 建议尺码：{ssize}"
    outfit["reason"] += f" 已根据您{profile.get('city', '')}的地理位置和{profile.get('daily_style', '休闲')}风格进行个性化调整。"
    return outfit


def get_mock_recommendations(query, profile, scenario):
    season = get_season(scenario.get('date', ''))
    occasion = scenario.get('occasion', '日常')
    season_data = MOCK_OUTFITS.get(season, MOCK_OUTFITS["秋季"])
    outfit = season_data.get(occasion, DEFAULT_OUTFIT)
    outfit = json.loads(json.dumps(outfit))  # deep copy
    outfit = personalize_outfit(outfit, profile)
    return {
        "mode": "mock",
        "query": query,
        "season": season,
        "outfits": [outfit["top"], outfit["bottom"], outfit["shoes"]],
        "reason": outfit["reason"]
    }


# ======================== 搜索函数（关键） ========================

def search_web(query, profile=None, scenario=None):
    """
    搜索函数 - 两种模式：
    模式 A（模拟演示）：SERPAPI_KEY 为 "your_key" 或空时，返回预设高质量模拟数据
    模式 B（真实搜索）：填入有效 Key 后，调用 SerpAPI 获取真实搜索结果
    """
    if SERPAPI_KEY and SERPAPI_KEY != "your_key" and SERPAPI_KEY.strip():
        # ====== 模式 B: 真实搜索 ======
        try:
            import requests as req
            params = {
                "q": query,
                "api_key": SERPAPI_KEY,
                "engine": "google",
                "num": 5
            }
            resp = req.get("https://serpapi.com/search", params=params, timeout=10)
            data = resp.json()
            results = []
            for item in data.get("organic_results", [])[:5]:
                results.append({
                    "title": item.get("title", ""),
                    "link": item.get("link", ""),
                    "snippet": item.get("snippet", "")
                })
            # 同时返回 mock 数据作为结构化推荐
            mock = get_mock_recommendations(query, profile, scenario)
            mock["mode"] = "real"
            mock["search_results"] = results
            return mock
        except Exception as e:
            print(f"[search_web] 真实搜索失败，回退模拟模式: {e}")
            return get_mock_recommendations(query, profile, scenario)
    else:
        # ====== 模式 A: 模拟演示 ======
        return get_mock_recommendations(query, profile, scenario)


# ======================== 路由 ========================

@app.route('/')
def index():
    profile = get_user_profile()
    return render_template('index.html', profile=profile)


@app.route('/save', methods=['POST'])
def save():
    data = request.form.to_dict()
    save_user_profile(data)
    return redirect(url_for('index'))


@app.route('/recommend')
def recommend():
    profile = get_user_profile()
    if not profile:
        return jsonify({"error": "请先填写用户画像"}), 400
    scenario = {
        "date": request.args.get('date', ''),
        "location": request.args.get('location', ''),
        "occasion": request.args.get('occasion', '')
    }
    query = build_search_query(profile, scenario)
    result = search_web(query, profile, scenario)
    return jsonify(result)


# ======================== 启动 ========================

if __name__ == '__main__':
    init_db()
    print("\033[92m🚀 点击打开：http://127.0.0.1:5000 \033[0m")
    app.run(debug=True, host='0.0.0.0')
