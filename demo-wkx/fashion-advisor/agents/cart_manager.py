"""
CartManager - 购物车管理子Agent
管理用户购物车：添加、查看、删除商品。
操作前需用户确认，执行后反馈结果。
"""

import sqlite3
import json
from datetime import datetime

class CartManager:
    def __init__(self, db_path):
        self.db_path = db_path

    def _get_db(self):
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def init_table(self):
        conn = self._get_db()
        conn.execute('''
            CREATE TABLE IF NOT EXISTS cart_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
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
                status TEXT DEFAULT 'pending'
            )
        ''')
        conn.commit()
        conn.close()

    def add_to_cart(self, item, selected_size=None):
        """将商品加入购物车"""
        conn = self._get_db()
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        sizes = item.get("sizes_available", [])
        if isinstance(sizes, list):
            sizes = json.dumps(sizes, ensure_ascii=False)
        conn.execute('''
            INSERT INTO cart_items
                (platform, product_name, price, shipping_fee, total_price,
                 seller, product_url, sizes_available, selected_size, added_at, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'added')
        ''', (
            item.get("platform", ""),
            item.get("product_name", ""),
            item.get("price", 0),
            item.get("shipping_fee", 0),
            item.get("total_price", item.get("price", 0)),
            item.get("seller", ""),
            item.get("product_url", "#"),
            sizes,
            selected_size,
            now,
        ))
        conn.commit()
        cart_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
        conn.close()
        return {
            "success": True,
            "cart_id": cart_id,
            "message": f"已将【{item.get('product_name', '商品')}】加入{item.get('platform', '')}购物车",
            "added_at": now,
        }

    def add_batch(self, items):
        """批量加入购物车，返回结果列表"""
        results = []
        for item in items:
            result = self.add_to_cart(item)
            results.append(result)
        total = sum(i.get("total_price", i.get("price", 0)) for i in items)
        return {
            "success": all(r["success"] for r in results),
            "count": len(results),
            "total_price": round(total, 2),
            "items": results,
            "message": f"已将 {len(results)} 件商品加入购物车，总计 \u00a5{round(total, 2)}",
        }

    def get_cart(self):
        """获取购物车内容"""
        conn = self._get_db()
        rows = conn.execute("SELECT * FROM cart_items ORDER BY added_at DESC").fetchall()
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
        return {
            "items": items,
            "count": len(items),
            "total_price": round(total, 2),
        }

    def remove_item(self, cart_id):
        """从购物车删除商品"""
        conn = self._get_db()
        conn.execute("DELETE FROM cart_items WHERE id = ?", (cart_id,))
        conn.commit()
        conn.close()
        return {"success": True, "message": f"已移除购物车商品 (ID:{cart_id})"}

    def clear_cart(self):
        """清空购物车"""
        conn = self._get_db()
        conn.execute("DELETE FROM cart_items")
        conn.commit()
        conn.close()
        return {"success": True, "message": "购物车已清空"}

    def confirm_checkout(self):
        """确认结算清单（不执行实际购买，仅展示）"""
        cart = self.get_cart()
        if cart["count"] == 0:
            return {"can_checkout": False, "message": "购物车为空"}
        return {
            "can_checkout": True,
            "message": f"购物车共 {cart['count']} 件商品，总计 \u00a5{cart['total_price']}",
            "items": cart["items"],
            "total_price": cart["total_price"],
            "warning": "请前往各平台App完成最终支付。本系统不代您执行支付操作。",
        }
