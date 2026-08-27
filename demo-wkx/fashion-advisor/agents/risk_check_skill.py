"""
RiskCheckSkill - 合规与风险检查子Agent
在输出推荐前进行合规性检查：
- 价格异常检测
- 敏感内容过滤
- 尺码建议合理性
- 平台可靠性验证
"""

import re

class RiskCheckSkill:
    SENSITIVE_WORDS = [
        "假货", "高仿", "A货", "走私", "违禁", "山寨", "外贸原单",
    ]

    UNREASONABLE_PRICE_THRESHOLD = {
        "上装": (10, 5000),
        "下装": (10, 3000),
        "外套": (20, 10000),
        "鞋履": (15, 5000),
        "配饰": (5, 2000),
    }

    def check(self, outfit_result, products=None, profile=None):
        """
        对穿搭方案和商品搜索结果进行合规与风险检查。
        返回: {passed: bool, warnings: [str], recommendations: [str]}
        """
        warnings = []
        recommendations = []

        if outfit_result:
            warnings.extend(self._check_outfit(outfit_result, profile))

        if products:
            for product in products:
                w = self._check_product(product)
                warnings.extend(w)

        if products:
            recs = self._check_price_reasonableness(products)
            recommendations.extend(recs)

        if profile:
            rec = self._check_size_advice(profile, products)
            if rec:
                recommendations.append(rec)

        passed = len(warnings) == 0
        return {
            "passed": passed,
            "warnings": warnings,
            "recommendations": recommendations,
        }

    def _check_outfit(self, outfit, profile):
        """检查穿搭方案的合规性"""
        warnings = []
        items = outfit.get("items", [])
        for item in items:
            name = item.get("name", "")
            desc = item.get("desc", "")
            text = f"{name} {desc}"
            for word in self.SENSITIVE_WORDS:
                if word in text:
                    warnings.append(f"敏感词检测: 单品'{name}'中包含不合规词汇'{word}'")
        reason = outfit.get("reason", "")
        if len(reason) < 10:
            warnings.append("推荐理由过于简短，请补充更详细的说明")
        return warnings

    def _check_product(self, product):
        """检查单个商品的合规性"""
        warnings = []
        name = product.get("product_name", "")
        for word in self.SENSITIVE_WORDS:
            if word in name:
                warnings.append(f"商品'{name}'疑似不合规，建议谨慎购买")
        if product.get("rating", 0) > 0 and product.get("rating", 0) < 3.5:
            warnings.append(f"商品'{name}'评分较低({product['rating']})，不推荐购买")
        if not product.get("in_stock", True):
            warnings.append(f"商品'{name}'可能缺货，请确认库存后再下单")
        return warnings

    def _check_price_reasonableness(self, products):
        """检测价格是否在合理区间"""
        recommendations = []
        for p in products:
            price = p.get("price", 0)
            if price <= 0:
                continue
            cat = p.get("category", "")
            if not cat:
                continue
            bounds = self.UNREASONABLE_PRICE_THRESHOLD.get(cat)
            if bounds:
                lo, hi = bounds
                if price < lo:
                    recommendations.append(f"'{p.get('product_name','')}'价格\u00a5{price}偏低，注意品质风险")
                elif price > hi:
                    recommendations.append(f"'{p.get('product_name','')}'价格\u00a5{price}偏高，建议对比市场均价")
        return recommendations

    def _check_size_advice(self, profile, products):
        """检查尺码建议合理性"""
        csize = profile.get("clothing_size", "")
        ssize = profile.get("shoe_size", "")
        height = profile.get("height")
        weight = profile.get("weight")
        if height and weight and csize:
            try:
                h = float(height)
                w = float(weight)
                bmi = w / ((h/100) ** 2) if h > 0 else 0
                if bmi < 18.5 and csize in ("L", "XL", "XXL"):
                    return "提示: 根据您的BMI({})偏瘦，建议尺码{}可能偏大，可考虑小一码".format(round(bmi,1), csize)
                if bmi > 28 and csize in ("XS", "S", "M"):
                    return "提示: 根据您的BMI({})偏胖，建议尺码{}可能偏小，可考虑大一码".format(round(bmi,1), csize)
            except:
                pass
        return None
