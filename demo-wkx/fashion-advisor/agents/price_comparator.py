"""
PriceComparator - 多维度比价子Agent
对ProductSearcher返回的搜索结果进行多维度比较：
- 券后价（含运费）
- 用户评分
- 销量
- 综合性价比评分
为每个单品输出最优购买推荐。
"""

class PriceComparator:
    def compare(self, search_results, budget_min=None, budget_max=None):
        """
        对搜索结果进行多维度比价。
        search_results: ProductSearcher返回的结果列表
        返回: 按平台分组的对比结果 + 最优推荐
        """
        if not search_results:
            return {"best": None, "comparison": [], "summary": "无搜索结果"}

        grouped = {}
        for r in search_results:
            platform = r.get("platform", "")
            if platform not in grouped:
                grouped[platform] = []
            grouped[platform].append(r)

        comparison = []
        for platform, items in grouped.items():
            for item in items:
                total_price = item.get("price", 0) + item.get("shipping_fee", 0)
                score = self._calc_score(item, total_price)
                comparison.append({
                    "platform": platform,
                    "platform_icon": item.get("platform_icon", ""),
                    "product_name": item.get("product_name", ""),
                    "price": item.get("price", 0),
                    "shipping_fee": item.get("shipping_fee", 0),
                    "total_price": round(total_price, 2),
                    "original_price": item.get("original_price", 0),
                    "discount": round((1 - item.get("price", 0) / item.get("original_price", 1)) * 100, 1) if item.get("original_price") else 0,
                    "rating": item.get("rating", 0),
                    "sales": item.get("sales", 0),
                    "seller": item.get("seller", ""),
                    "product_url": item.get("product_url", "#"),
                    "in_stock": item.get("in_stock", True),
                    "sizes_available": item.get("sizes_available", []),
                    "score": round(score, 2),
                    "source": item.get("source", "mock"),
                })

        comparison.sort(key=lambda x: x["score"], reverse=True)

        best = comparison[0] if comparison else None
        if best:
            best["recommend_reason"] = self._gen_reason(best)

        cheapest = min(comparison, key=lambda x: x["total_price"]) if comparison else None
        highest_rated = max(comparison, key=lambda x: x["rating"]) if comparison else None

        summary = self._gen_summary(best, cheapest, comparison, budget_min, budget_max)

        return {
            "best": best,
            "cheapest": cheapest,
            "highest_rated": highest_rated,
            "comparison": comparison,
            "summary": summary,
        }

    def _calc_score(self, item, total_price):
        """综合性价比评分算法：价格40% + 评分30% + 销量20% + 平台10%"""
        all_prices = [item.get("price", 0)]
        max_price = max(all_prices) if all_prices else 1
        price_score = (1 - total_price / (max_price * 1.5)) * 40 if max_price > 0 else 20

        rating = item.get("rating", 0)
        rating_score = (rating / 5.0) * 30

        sales = item.get("sales", 0)
        sales_score = min(sales / 10000, 1) * 20

        platform_bonus = {
            "淘宝": 8, "唯品会": 9, "抖音": 7, "拼多多": 6,
        }.get(item.get("platform", ""), 5)

        return max(0, price_score + rating_score + sales_score + platform_bonus)

    def _gen_reason(self, best):
        """生成最优推荐理由"""
        reasons = []
        if best.get("discount", 0) > 20:
            reasons.append(f"折扣力度大({best['discount']}% off)")
        if best.get("rating", 0) >= 4.5:
            reasons.append(f"用户评分高({best['rating']}\u2b50)")
        if best.get("shipping_fee", 0) == 0:
            reasons.append("包邮")
        if best.get("sales", 0) > 5000:
            reasons.append(f"销量高({best['sales']}+)")
        if best.get("platform") == "唯品会":
            reasons.append("品牌正品保障")
        elif best.get("platform") == "淘宝":
            reasons.append("平台选择丰富")
        elif best.get("platform") == "拼多多":
            reasons.append("价格优势明显")
        elif best.get("platform") == "抖音":
            reasons.append("直播验货便捷")
        if not reasons:
            reasons.append("综合性价比最优")
        return "\u3001".join(reasons[:3])

    def _gen_summary(self, best, cheapest, comparison, budget_min, budget_max):
        """生成比价总结"""
        if not best:
            return "暂无比价结果"

        parts = []
        if cheapest and best and cheapest["total_price"] < best["total_price"]:
            parts.append(f"最低价: {cheapest['platform']} \u00a5{cheapest['total_price']}")
            parts.append(f"综合最优: {best['platform']} \u00a5{best['total_price']} (评分{best.get('rating',0)})")
        else:
            parts.append(f"推荐: {best['platform']} \u00a5{best['total_price']} (综合评分{best['score']})")

        if budget_min and budget_max:
            in_budget = [c for c in comparison if budget_min <= c["total_price"] <= budget_max]
            parts.append(f"预算内(\u00a5{budget_min}-\u00a5{budget_max})选择: {len(in_budget)}款")

        parts.append(f"共比较 {len(comparison)} 个商品")
        return " | ".join(parts)
