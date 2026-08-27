import urllib.request, urllib.parse, json

class WeatherTool:
    CITY_COORDS = {
        "北京": (39.9075, 116.39723), "上海": (31.22222, 121.45806),
        "广州": (23.12911, 113.26438), "深圳": (22.54554, 114.06867),
        "杭州": (30.29365, 120.16142), "南京": (32.06167, 118.77778),
        "武汉": (30.58333, 114.26667), "成都": (30.66667, 104.06667),
        "重庆": (29.56284, 106.55273), "西安": (34.25833, 108.92861),
        "天津": (39.14222, 117.17667), "苏州": (31.29933, 120.61944),
        "长沙": (28.19875, 112.97150), "郑州": (34.75356, 113.62750),
        "青岛": (36.06711, 120.38260), "大连": (38.91250, 121.60222),
        "厦门": (24.47978, 118.08194), "昆明": (25.03889, 102.71833),
        "哈尔滨": (45.75000, 126.65000), "沈阳": (41.80583, 123.43278),
        "济南": (36.86700, 116.20000), "福州": (26.06139, 119.30611),
        "合肥": (31.82057, 117.22722), "南宁": (22.81667, 108.31667),
        "海口": (20.03333, 110.33333), "三亚": (18.25278, 109.51222),
        "拉萨": (29.65000, 91.10000), "乌鲁木齐": (43.80000, 87.58333),
        "石家庄": (38.04167, 114.47861), "长春": (43.86667, 125.31667),
        "宁波": (29.87528, 121.54417), "温州": (27.99944, 120.66667),
        "无锡": (31.56667, 120.28333), "沭阳": (34.86167, 118.58611),
        "宿迁": (33.96333, 118.27556),
    }

    WMO_MAP = {
        0: ("晴", "sunny"), 1: ("多云", "partly_cloudy"), 2: ("阴天", "cloudy"),
        3: ("阴", "overcast"), 45: ("雾", "fog"), 48: ("雾凇", "fog"),
        51: ("毛毛雨", "drizzle"), 53: ("毛毛雨", "drizzle"), 55: ("毛毛雨", "drizzle"),
        61: ("小雨", "light_rain"), 63: ("中雨", "rain"), 65: ("大雨", "heavy_rain"),
        71: ("小雪", "light_snow"), 73: ("中雪", "snow"), 75: ("大雪", "heavy_snow"),
        80: ("阵雨", "shower"), 81: ("中阵雨", "shower"), 82: ("大阵雨", "heavy_shower"),
        95: ("雷阵雨", "thunderstorm"), 96: ("雷阵雨伴冰雹", "thunderstorm"),
    }

    def get_weather(self, city):
        coords = self.CITY_COORDS.get(city)
        if not coords:
            coords = self._geocode(city)
        if not coords:
            return {"city": city, "temp": 20, "desc": "未知", "advice": "无法获取天气，默认20°C"}
        return self._fetch_weather(coords, city)

    def get_weather_by_address(self, address_parts):
        """根据逐级地址（国/省/市/县）获取天气"""
        country = address_parts.get("country", "中国")
        province = address_parts.get("province", "")
        city = address_parts.get("city", "")
        county = address_parts.get("county", "")

        # 地址归一化：去掉常见后缀以便匹配坐标表
        def normalize(name):
            if not name:
                return name
            for suffix in ["壮族自治区", "回族自治区", "维吾尔自治区", "特别行政区",
                           "自治区", "省", "市", "区", "县", "镇", "乡", "村",
                           "新区", "高新区", "开发区"]:
                if name.endswith(suffix) and len(name) > len(suffix):
                    return name[:-len(suffix)]
            return name

        # 构建搜索关键词：优先使用最具体的地址
        search_parts = [p for p in [county, city, province] if p]
        if not search_parts:
            search_parts = [country]
        search_name = " ".join(search_parts)

        # 先查内置坐标表（用归一化后的名称）
        for part in [county, city, province]:
            if not part:
                continue
            normalized = normalize(part)
            if normalized in self.CITY_COORDS:
                return self._fetch_weather(self.CITY_COORDS[normalized], city or province)

        # 调用 geocoding API（用归一化后的关键词）
        normalized_search = " ".join(normalize(p) for p in search_parts)
        coords = self._geocode(normalized_search)
        if coords:
            return self._fetch_weather(coords, city or province or country)

        # 逐步缩减关键词重试
        for i in range(len(search_parts), 0, -1):
            sub = " ".join(normalize(p) for p in search_parts[:i])
            coords = self._geocode(sub)
            if coords:
                return self._fetch_weather(coords, city or province or country)

        return {"city": city or province, "temp": 20, "desc": "未知",
                "advice": "无法定位该地址，默认20°C"}

    def _fetch_weather(self, coords, place_name):
        """根据坐标获取天气数据"""
        try:
            lat, lon = coords
            url = (f"https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}"
                   f"&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m"
                   f"&timezone=Asia%2FShanghai")
            with urllib.request.urlopen(url, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            cur = data.get("current", {})
            code = cur.get("weather_code", 0)
            desc_cn, desc_en = self.WMO_MAP.get(code, ("未知", "unknown"))
            temp = cur.get("temperature_2m", 20)
            feels = cur.get("apparent_temperature", temp)
            humidity = cur.get("relative_humidity_2m", 50)
            wind = cur.get("wind_speed_10m", 0)
            return {
                "city": place_name, "temp": temp, "feels_like": feels,
                "desc": desc_cn, "desc_en": desc_en,
                "humidity": humidity, "wind_speed": wind,
                "advice": self._clothing_advice(temp, code)
            }
        except Exception as e:
            return {"city": place_name, "temp": 20, "desc": "获取失败",
                    "advice": f"天气获取失败: {e}"}

    def _geocode(self, name):
        try:
            url = (f"https://geocoding-api.open-meteo.com/v1/search"
                   f"?name={urllib.parse.quote(name)}&count=1&language=zh")
            with urllib.request.urlopen(url, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            results = data.get("results", [])
            if results:
                return (results[0]["latitude"], results[0]["longitude"])
        except:
            pass
        return None

    def _clothing_advice(self, temp, code):
        if temp >= 28:
            return "炎热，建议短袖、短裤、凉鞋等清凉透气穿搭"
        elif temp >= 22:
            return "温暖，短袖或薄长袖均可，轻便舒适"
        elif temp >= 15:
            return "凉爽，建议薄外套+长袖，早晚温差注意"
        elif temp >= 5:
            return "偏冷，建议毛衣/夹克/风衣+长裤，注意保暖"
        elif temp >= -5:
            return "寒冷，建议羽绒服/大衣+毛衣+保暖裤"
        else:
            return "严寒，建议厚羽绒服+多层保暖+围巾手套"
