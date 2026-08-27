import re

class IntentRouter:
    INTENT_PATTERNS = {
        "outfit_request": ["穿什么", "搭配", "穿搭", "推荐.*穿", "出门.*穿", "面试.*穿", "约会.*穿", "帮我.*搭"],
        "price_comparison": ["比价", "哪个便宜", "多少钱", "价格", "划算", "性价比"],
        "size_advice": ["尺码", "多大", "合身", "尺寸", "偏大", "偏小"],
        "style_advice": ["风格", "好看", "适合.*风格", "流行", "时尚", "趋势"],
        "cart_operation": ["加购", "加入购物车", "买", "下单", "购物车"],
        "wardrobe": ["衣橱", "衣柜", "有什么衣服", "已有"],
        "weather_query": ["天气", "温度", "几度", "冷不", "热不"],
    }

    def classify(self, message):
        if not message:
            return "general"
        msg = message.strip().lower()
        for intent, keywords in self.INTENT_PATTERNS.items():
            for kw in keywords:
                if re.search(kw, msg):
                    return intent
        return "general"
