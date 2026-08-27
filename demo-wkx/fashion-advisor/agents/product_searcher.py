"""
ProductSearcher - 多平台商品检索子Agent
根据穿搭方案中的单品描述，在多平台（淘宝、拼多多、抖音、唯品会）搜索具体商品。
支持两种模式：模拟演示（默认）和真实API搜索（需配置API Key）。
"""

import os
import json
import urllib.request
import urllib.parse
import hashlib
import random

class ProductSearcher:
    PLATFORMS = ["淘宝", "拼多多", "抖音", "唯品会"]

    PLATFORM_ICONS = {
        "淘宝": "\U0001f351",
        "拼多多": "\u2728",
        "抖音": "\U0001f3a5",
        "唯品会": "\U0001f4b0",
    }

    def __init__(self, api_key=None):
        self.api_key = api_key or os.environ.get("SERPAPI_KEY", "")

    def search(self, item, budget_min=None, budget_max=None):
        """
        根据单品信息在多平台搜索商品。
        item: {"name": "黑色羊毛大衣", "category": "外套", "color": "黑色", "material": "羊毛"}
        返回: 各平台搜索结果列表
        """
        query = self._build_query(item)

        if self.api_key and self.api_key.strip():
            return self._real_search(query, item, budget_min, budget_max)
        return self._mock_search(query, item, budget_min, budget_max)

    def _build_query(self, item):
        name = item.get("name", "")
        color = item.get("color", "")
        material = item.get("material", "")
        return f"{color} {material} {name}".strip()

    def _mock_search(self, query, item, budget_min, budget_max):
        """模拟多平台搜索结果 - 基于单品特征生成合理的商品数据"""
        base_price = self._estimate_price(item)
        results = []
        for platform in self.PLATFORMS:
            # 每个平台生成1-3个结果
            num = random.randint(1, 3)
            for i in range(num):
                price = self._vary_price(base_price, platform)
                if budget_min and budget_max:
                    if price < budget_min or price > budget_max:
                        price = random.uniform(budget_min, budget_max)
                results.append({
                    "platform": platform,
                    "platform_icon": self.PLATFORM_ICONS.get(platform, "\u2728"),
                    "product_name": self._gen_product_name(item, platform, i),
                    "price": round(price, 2),
                    "original_price": round(price * random.uniform(1.15, 1.5), 2),
                    "shipping_fee": self._get_shipping_fee(platform, price),
                    "rating": round(random.uniform(4.2, 4.9), 1),
                    "sales": random.randint(100, 50000),
                    "seller": self._gen_seller(platform),
                    "product_url": self._gen_search_url(platform, query),
                    "in_stock": True,
                    "sizes_available": self._gen_sizes(item),
                    "query": query,
                    "source": "mock",
                })
        results.sort(key=lambda x: x["price"])
        return results

    def _real_search(self, query, item, budget_min, budget_max):
        """真实API搜索 - 使用SerpAPI搜索各平台商品"""
        try:
            results = []
            for platform in self.PLATFORMS:
                search_query = f"{query} site:{self._get_platform_domain(platform)}"
                params = {
                    "q": search_query,
                    "api_key": self.api_key,
                    "engine": "google",
                    "num": 3,
                }
                url = "https://serpapi.com/search?" + urllib.parse.urlencode(params)
                with urllib.request.urlopen(url, timeout=15) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                for item_data in data.get("organic_results", [])[:3]:
                    results.append({
                        "platform": platform,
                        "platform_icon": self.PLATFORM_ICONS.get(platform, "\u2728"),
                        "product_name": item_data.get("title", ""),
                        "price": self._extract_price(item_data.get("snippet", "")),
                        "shipping_fee": 0,
                        "rating": 0,
                        "sales": 0,
                        "seller": "",
                        "product_url": item_data.get("link", "#"),
                        "in_stock": True,
                        "sizes_available": [],
                        "query": query,
                        "source": "real",
                        "snippet": item_data.get("snippet", ""),
                    })
            return results
        except Exception as e:
            print(f"[ProductSearcher] 真实搜索失败，回退模拟: {e}")
            return self._mock_search(query, item, budget_min, budget_max)

    def _get_platform_domain(self, platform):
        domains = {
            "淘宝": "taobao.com",
            "拼多多": "pinduoduo.com",
            "抖音": "douyin.com",
            "唯品会": "vip.com",
        }
        return domains.get(platform, "taobao.com")

    def _gen_search_url(self, platform, query):
        """为各平台生成真实可点击的搜索URL"""
        encoded = urllib.parse.quote(query)
        urls = {
            "淘宝": f"https://s.taobao.com/search?q={encoded}",
            "拼多多": f"https://mobile.yangkeduo.com/search_result.html?search_key={encoded}",
            "抖音": f"https://www.douyin.com/search/{encoded}",
            "唯品会": f"https://category.vip.com/s/search.php?q={encoded}",
        }
        return urls.get(platform, f"https://s.taobao.com/search?q={encoded}")

    def _estimate_price(self, item):
        """根据单品类别和面料估算合理价格区间"""
        category = item.get("category", "")
        material = item.get("material", "")
        base = {
            "上装": 150, "下装": 120, "外套": 300, "鞋履": 200, "配饰": 80,
        }.get(category, 150)
        material_mult = 1.0
        if "羊绒" in material: material_mult = 2.5
        elif "羊毛" in material: material_mult = 1.8
        elif "丝" in material or "丝绒" in material: material_mult = 2.0
        elif "牛皮" in material or "皮革" in material: material_mult = 1.6
        elif "亚麻" in material: material_mult = 1.3
        elif "棉" in material: material_mult = 1.0
        elif "涤纶" in material: material_mult = 0.8
        return base * material_mult

    def _vary_price(self, base, platform):
        mult = {
            "淘宝": random.uniform(0.8, 1.3),
            "拼多多": random.uniform(0.5, 0.9),
            "抖音": random.uniform(0.9, 1.4),
            "唯品会": random.uniform(0.7, 1.1),
        }.get(platform, 1.0)
        return base * mult

    def _get_shipping_fee(self, platform, price):
        if platform == "拼多多":
            return 0
        if platform == "淘宝" and price >= 88:
            return 0
        if platform == "唯品会":
            return 0 if price >= 288 else 10
        if platform == "抖音":
            return 0 if price >= 50 else 8
        return random.choice([0, 8, 10, 12])

    def _gen_product_name(self, item, platform, idx):
        name = item.get("name", "商品")
        color = item.get("color", "")
        material = item.get("material", "")
        suffixes = ["旗舰款", "热销款", "新品", "爆款", "精选", "专供"]
        prefix = ["2024新款", "品牌", "直供", "官方", "工厂"]
        parts = []
        if random.random() > 0.5:
            parts.append(random.choice(prefix))
        if color:
            parts.append(color)
        parts.append(name)
        if material:
            parts.append(f"({material})")
        parts.append(random.choice(suffixes))
        if idx > 0:
            parts.append(f"#{idx+1}")
        return "".join(parts)

    def _gen_seller(self, platform):
        prefixes = ["优品", "精选", "潮流", "风尚", "品质", "优选", "旗舰", "直营"]
        suffixes = ["旗舰店", "专卖店", "直供店", "工厂店", "专营店"]
        return f"{random.choice(prefixes)}{random.choice(suffixes)}"

    def _gen_sizes(self, item):
        category = item.get("category", "")
        if category == "鞋履":
            return ["39", "40", "41", "42", "43", "44"]
        elif category in ("上装", "外套"):
            return ["S", "M", "L", "XL", "XXL"]
        elif category == "下装":
            return ["28", "30", "32", "34", "36"]
        return ["均码"]

    def _extract_price(self, text):
        import re
        match = re.search(r'\u00a5(\d+\.?\d*)', text)
        if match:
            return float(match.group(1))
        match = re.search(r'(\d+\.?\d*)\u5143', text)
        if match:
            return float(match.group(1))
        return 0
