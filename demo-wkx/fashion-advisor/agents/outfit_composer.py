import json, copy
from datetime import datetime

class OutfitComposer:
    OUTFITS = {
        "春季": {
            "面试": {
                "top": {"category":"上装","name":"白色棉质衬衫","color":"白色","material":"高支棉","desc":"经典白色衬衫，干净利落，面试首选"},
                "bottom": {"category":"下装","name":"藏青色修身西裤","color":"藏青色","material":"羊毛混纺","desc":"修身剪裁，显腿长，专业感强"},
                "outer": {"category":"外套","name":"深灰色单排扣西装","color":"深灰色","material":"羊毛","desc":"合身西装，挺括有型，提升气场"},
                "shoes": {"category":"鞋履","name":"黑色牛皮德比鞋","color":"黑色","material":"牛皮","desc":"经典德比鞋，正式且百搭"},
                "accessories": {"category":"配饰","name":"深蓝色真丝领带","color":"深蓝色","material":"真丝","desc":"低调纹理领带，增添专业气质"},
                "reason":"春季面试推荐白衬衫+藏青西裤+深灰西装的经典组合，干练专业。黑色德比鞋稳重百搭，深蓝领带增添细节感。"
            },
            "约会": {
                "top": {"category":"上装","name":"奶白色针织Polo衫","color":"奶白色","material":"棉质针织","desc":"柔软针织面料，Polo领休闲中带精致"},
                "bottom": {"category":"下装","name":"卡其色修身休闲裤","color":"卡其色","material":"棉","desc":"修身休闲版型，百搭卡其色"},
                "outer": {"category":"外套","name":"浅蓝色牛仔夹克","color":"浅蓝色","material":"棉质牛仔","desc":"经典牛仔夹克，春季叠穿利器"},
                "shoes": {"category":"鞋履","name":"白色帆布板鞋","color":"白色","material":"帆布","desc":"清新白色，适合春季约会"},
                "accessories": {"category":"配饰","name":"棕色编织皮带","color":"棕色","material":"牛皮","desc":"点缀细节，提升整体精致度"},
                "reason":"春季约会推荐奶白针织+卡其休闲裤的温柔搭配，牛仔夹克增添层次感，白色板鞋清爽活力。"
            },
            "日常通勤": {
                "top": {"category":"上装","name":"薄荷绿圆领T恤","color":"薄荷绿","material":"纯棉","desc":"春季清新色系，纯棉透气舒适"},
                "bottom": {"category":"下装","name":"浅蓝色直筒牛仔裤","color":"浅蓝色","material":"弹力牛仔","desc":"经典直筒版型，日常百搭"},
                "outer": {"category":"外套","name":"米色轻薄风衣","color":"米色","material":"涤纶混纺","desc":"轻薄防风，春季通勤实用"},
                "shoes": {"category":"鞋履","name":"米色帆布鞋","color":"米色","material":"帆布","desc":"百搭米色，日常出行首选"},
                "accessories": {"category":"配饰","name":"黑色双肩包","color":"黑色","material":"尼龙","desc":"通勤实用，容量大"},
                "reason":"春季日常通勤以舒适清新为主，薄荷绿+浅蓝自然色调，米色风衣防风实用。"
            },
            "婚礼": {
                "top": {"category":"上装","name":"浅粉色修身衬衫","color":"浅粉色","material":"棉混纺","desc":"春季婚礼温馨色调，修身剪裁"},
                "bottom": {"category":"下装","name":"米白色高腰西裤","color":"米白色","material":"精纺羊毛","desc":"高腰修身，拉长腿部线条"},
                "outer": {"category":"外套","name":"浅灰色休闲西装","color":"浅灰色","material":"棉麻混纺","desc":"休闲西装，不太正式但得体"},
                "shoes": {"category":"鞋履","name":"香槟色乐福鞋","color":"香槟色","material":"丝绒","desc":"丝绒材质，婚礼场合精致感"},
                "accessories": {"category":"配饰","name":"粉色胸花胸针","color":"粉色","material":"丝绸","desc":"婚礼胸花，增添仪式感"},
                "reason":"春季婚礼以浅色系为主，浅粉+米白温柔优雅，香槟丝绒鞋增添精致感。"
            },
            "派对": {
                "top": {"category":"上装","name":"黑色丝质衬衫","color":"黑色","material":"丝质","desc":"丝质光泽感，派对场合吸睛"},
                "bottom": {"category":"下装","name":"黑色修身休闲裤","color":"黑色","material":"棉","desc":"全黑搭配，酷感十足"},
                "outer": {"category":"外套","name":"酒红色丝绒西装外套","color":"酒红色","material":"丝绒","desc":"丝绒光泽+酒红色，派对焦点"},
                "shoes": {"category":"鞋履","name":"黑色切尔西靴","color":"黑色","material":"牛皮","desc":"切尔西靴，时尚百搭"},
                "accessories": {"category":"配饰","name":"银色项链","color":"银色","material":"925银","desc":"细节点缀，提升时尚感"},
                "reason":"春季派对推荐黑色丝质衬衫+酒红丝绒西装，光泽质感组合派对感十足。"
            },
            "旅行": {
                "top": {"category":"上装","name":"灰色圆领卫衣","color":"灰色","material":"棉混纺","desc":"舒适卫衣，旅行首选"},
                "bottom": {"category":"下装","name":"黑色弹力运动裤","color":"黑色","material":"弹力面料","desc":"弹力舒适，活动自如"},
                "outer": {"category":"外套","name":"军绿色冲锋衣","color":"军绿色","material":"防水面料","desc":"防风防雨，旅行实用"},
                "shoes": {"category":"鞋履","name":"黑色运动鞋","color":"黑色","material":"网布+橡胶","desc":"舒适运动鞋，长时间行走"},
                "accessories": {"category":"配饰","name":"黑色鸭舌帽","color":"黑色","material":"棉","desc":"防晒遮阳，旅行必备"},
                "reason":"春季旅行以舒适实用为主，灰色卫衣+黑色运动裤轻松自在，冲锋衣应对多变天气。"
            },
            "休闲": {
                "top": {"category":"上装","name":"白色圆领T恤","color":"白色","material":"纯棉","desc":"基础白T，万能百搭"},
                "bottom": {"category":"下装","name":"灰色运动短裤","color":"灰色","material":"棉混纺","desc":"舒适运动短裤，休闲首选"},
                "outer": {"category":"外套","name":"浅蓝色牛仔夹克","color":"浅蓝色","material":"棉质牛仔","desc":"经典牛仔夹克，休闲叠穿"},
                "shoes": {"category":"鞋履","name":"白色老爹鞋","color":"白色","material":"网布+橡胶","desc":"潮流老爹鞋，舒适百搭"},
                "accessories": {"category":"配饰","name":"黑色帆布托特包","color":"黑色","material":"帆布","desc":"大容量托特包，日常实用"},
                "reason":"春季休闲推荐白T+灰短裤的极简搭配，牛仔夹克增添层次，老爹鞋潮流舒适。"
            }
        },
        "夏季": {
            "面试": {
                "top": {"category":"上装","name":"天蓝色短袖衬衫","color":"天蓝色","material":"高支棉","desc":"高支棉透气挺括，天蓝清爽专业"},
                "bottom": {"category":"下装","name":"深灰色修身西裤","color":"深灰色","material":"轻薄羊毛","desc":"轻薄面料适合夏季，深色稳重"},
                "outer": {"category":"外套","name":"浅灰色薄款西装","color":"浅灰色","material":"棉麻混纺","desc":"轻薄透气，空调房可穿"},
                "shoes": {"category":"鞋履","name":"黑色乐福鞋","color":"黑色","material":"牛皮","desc":"无系带设计，穿脱方便且正式"},
                "accessories": {"category":"配饰","name":"银色手表","color":"银色","material":"不锈钢","desc":"简约手表，提升专业感"},
                "reason":"夏季面试推荐天蓝短袖+深灰西裤，清爽专业的配色，黑色乐福鞋兼顾空调房与通勤。"
            },
            "约会": {
                "top": {"category":"上装","name":"珊瑚粉短袖衬衫","color":"珊瑚粉","material":"棉麻混纺","desc":"珊瑚粉浪漫温柔，棉麻透气"},
                "bottom": {"category":"下装","name":"白色修身休闲裤","color":"白色","material":"棉","desc":"白色裤装清爽时尚"},
                "outer": {"category":"外套","name":"米色薄款针织开衫","color":"米色","material":"棉","desc":"薄款针织，空调房备用"},
                "shoes": {"category":"鞋履","name":"米色麂皮乐福鞋","color":"米色","material":"麂皮","desc":"米色温柔百搭，麂皮质感佳"},
                "accessories": {"category":"配饰","name":"棕色编织手环","color":"棕色","material":"牛皮","desc":"手工编织手环，增添细节"},
                "reason":"夏季约会推荐珊瑚粉+白色的浪漫搭配，清爽有活力，米色乐福鞋增添成熟魅力。"
            },
            "日常通勤": {
                "top": {"category":"上装","name":"白色基础款T恤","color":"白色","material":"纯棉","desc":"夏季必备白T，百搭透气"},
                "bottom": {"category":"下装","name":"藏蓝色五分裤","color":"藏蓝色","material":"棉混纺","desc":"五分裤清凉，藏蓝百搭"},
                "outer": {"category":"外套","name":"浅灰色薄衬衫外套","color":"浅灰色","material":"棉","desc":"薄衬衫作外套，防晒又透气"},
                "shoes": {"category":"鞋履","name":"白色运动凉鞋","color":"白色","material":"EVA","desc":"轻便凉爽，夏季日常"},
                "accessories": {"category":"配饰","name":"黑色斜挎包","color":"黑色","material":"尼龙","desc":"轻便斜挎，通勤实用"},
                "reason":"夏季通勤以清凉为主，白T+藏蓝短裤简单舒适，薄衬衫外套防晒透气。"
            },
            "婚礼": {
                "top": {"category":"上装","name":"白色亚麻衬衫","color":"白色","material":"亚麻","desc":"透气亚麻，白色清爽正式"},
                "bottom": {"category":"下装","name":"浅灰色西装短裤","color":"浅灰色","material":"棉混纺","desc":"修身短裤，兼顾正式与清凉"},
                "outer": {"category":"外套","name":"米白色亚麻西装","color":"米白色","material":"亚麻","desc":"亚麻西装，夏季婚礼优雅之选"},
                "shoes": {"category":"鞋履","name":"棕色编织皮带凉鞋","color":"棕色","material":"牛皮","desc":"编织皮凉鞋，正式感适中"},
                "accessories": {"category":"配饰","name":"香槟色领结","color":"香槟色","material":"丝质","desc":"领结增添婚礼仪式感"},
                "reason":"夏季婚礼推荐白色亚麻衬衫，透气有质感；浅灰短裤清凉不失正式。"
            },
            "派对": {
                "top": {"category":"上装","name":"黑色短袖丝质衬衫","color":"黑色","material":"丝质","desc":"丝质光泽，夏季派对吸睛"},
                "bottom": {"category":"下装","name":"白色修身休闲裤","color":"白色","material":"棉","desc":"黑白对比，视觉冲击"},
                "outer": {"category":"外套","name":"黑色薄款西装外套","color":"黑色","material":"棉麻混纺","desc":"薄款西装，夏季可穿"},
                "shoes": {"category":"鞋履","name":"白色乐福鞋","color":"白色","material":"牛皮","desc":"白色乐福，清爽时尚"},
                "accessories": {"category":"配饰","name":"金色项链","color":"金色","material":"镀金","desc":"金色点缀，派对感十足"},
                "reason":"夏季派对推荐黑色丝质衬衫+白色裤装的黑白配，经典又有视觉冲击。"
            },
            "旅行": {
                "top": {"category":"上装","name":"白色速干短袖","color":"白色","material":"速干面料","desc":"速干透气，旅行首选"},
                "bottom": {"category":"下装","name":"卡其色多功能裤","color":"卡其色","material":"尼龙","desc":"多功能口袋，旅行实用"},
                "outer": {"category":"外套","name":"白色防晒薄外套","color":"白色","material":"防晒面料","desc":"UPF50+防晒，夏季旅行必备"},
                "shoes": {"category":"鞋履","name":"灰色运动凉鞋","color":"灰色","material":"EVA+橡胶","desc":"透气凉鞋，适合夏季旅行"},
                "accessories": {"category":"配饰","name":"黑色遮阳帽","color":"黑色","material":"速干面料","desc":"宽檐遮阳帽，防晒利器"},
                "reason":"夏季旅行以防晒透气为重，速干短袖+多功能裤+防晒外套，全面应对炎热。"
            },
            "休闲": {
                "top": {"category":"上装","name":"条纹短袖T恤","color":"蓝白条纹","material":"纯棉","desc":"经典海魂衫条纹，夏日休闲"},
                "bottom": {"category":"下装","name":"米色五分短裤","color":"米色","material":"棉","desc":"米色五分裤，清爽百搭"},
                "outer": {"category":"外套","name":"白色薄衬衫","color":"白色","material":"棉","desc":"薄衬衫作外套，防晒叠穿"},
                "shoes": {"category":"鞋履","name":"蓝色帆布鞋","color":"蓝色","material":"帆布","desc":"蓝色帆布鞋，夏日清新"},
                "accessories": {"category":"配饰","name":"草编手提包","color":"原色","material":"草编","desc":"草编包，夏日度假风"},
                "reason":"夏季休闲推荐条纹T恤+米色短裤，海魂衫元素增添夏日气息，草编包度假感十足。"
            }
        },
        "秋季": {
            "面试": {
                "top": {"category":"上装","name":"白色高支棉衬衫","color":"白色","material":"高支棉","desc":"经典白衬衫，面试标配"},
                "bottom": {"category":"下装","name":"炭灰色修身西裤","color":"炭灰色","material":"羊毛","desc":"炭灰色稳重，修身版型"},
                "outer": {"category":"外套","name":"藏青色双排扣西装","color":"藏青色","material":"羊毛","desc":"双排扣气场足，藏青经典专业"},
                "shoes": {"category":"鞋履","name":"黑色亮面牛津鞋","color":"黑色","material":"牛皮","desc":"亮面牛津鞋，提升正式感"},
                "accessories": {"category":"配饰","name":"酒红色真丝领带","color":"酒红色","material":"真丝","desc":"酒红领带，秋季暖色调点缀"},
                "reason":"秋季面试推荐白衬衫+炭灰西裤+藏青双排扣西装，气场强大专业感强。"
            },
            "约会": {
                "top": {"category":"上装","name":"焦糖色圆领毛衣","color":"焦糖色","material":"羊绒混纺","desc":"焦糖色温暖浪漫，羊绒柔软"},
                "bottom": {"category":"下装","name":"深棕色灯芯绒裤","color":"深棕色","material":"灯芯绒","desc":"灯芯绒复古有质感"},
                "outer": {"category":"外套","name":"驼色长款风衣","color":"驼色","material":"棉","desc":"驼色风衣，秋季约会经典"},
                "shoes": {"category":"鞋履","name":"棕色麂皮切尔西靴","color":"棕色","material":"麂皮","desc":"切尔西靴时尚百搭"},
                "accessories": {"category":"配饰","name":"棕色编织皮带","color":"棕色","material":"牛皮","desc":"皮带与鞋色呼应"},
                "reason":"秋季约会推荐焦糖毛衣+灯芯绒裤，暖色调温馨浪漫，驼色风衣经典优雅。"
            },
            "日常通勤": {
                "top": {"category":"上装","name":"燕麦色连帽卫衣","color":"燕麦色","material":"棉混纺","desc":"燕麦色温柔百搭，连帽休闲"},
                "bottom": {"category":"下装","name":"黑色直筒休闲裤","color":"黑色","material":"棉","desc":"黑色直筒裤，百搭利落"},
                "outer": {"category":"外套","name":"深灰色棒球夹克","color":"深灰色","material":"棉","desc":"棒球夹克，休闲运动风"},
                "shoes": {"category":"鞋履","name":"白色老爹运动鞋","color":"白色","material":"网布+橡胶","desc":"老爹鞋潮流舒适"},
                "accessories": {"category":"配饰","name":"黑色双肩包","color":"黑色","material":"尼龙","desc":"通勤实用背包"},
                "reason":"秋季日常推荐燕麦卫衣+黑色休闲裤，舒适自在，棒球夹克增添运动风。"
            },
            "婚礼": {
                "top": {"category":"上装","name":"深蓝色丝绒西装外套","color":"深蓝色","material":"丝绒","desc":"光泽丝绒，修身剪裁，婚礼正式"},
                "bottom": {"category":"下装","name":"黑色高腰修身西裤","color":"黑色","material":"羊毛","desc":"修身高腰，拉长腿部线条"},
                "outer": {"category":"外套","name":"深蓝色丝绒西装","color":"深蓝色","material":"丝绒","desc":"（与上装一体）"},
                "shoes": {"category":"鞋履","name":"棕色牛皮牛津鞋","color":"棕色","material":"牛皮","desc":"经典牛津鞋，棕色增添暖意"},
                "accessories": {"category":"配饰","name":"白色口袋巾","color":"白色","material":"丝质","desc":"口袋巾点缀，增添精致感"},
                "reason":"秋季婚礼以深色系为主，深蓝丝绒+黑色西裤沉稳大气，棕色牛津鞋暖色调。"
            },
            "派对": {
                "top": {"category":"上装","name":"黑色高领毛衣","color":"黑色","material":"羊绒","desc":"黑色高领，酷感十足"},
                "bottom": {"category":"下装","name":"黑色修身皮裤","color":"黑色","material":"皮革","desc":"皮裤增添派对摇滚感"},
                "outer": {"category":"外套","name":"豹纹印花西装","color":"豹纹","material":"涤纶","desc":"豹纹西装，派对焦点"},
                "shoes": {"category":"鞋履","name":"黑色皮靴","color":"黑色","material":"牛皮","desc":"黑色皮靴，酷感升级"},
                "accessories": {"category":"配饰","name":"金色粗项链","color":"金色","material":"镀金","desc":"粗项链，派对感十足"},
                "reason":"秋季派对推荐全黑底色+豹纹西装外套，大胆配色彰显个性。"
            },
            "旅行": {
                "top": {"category":"上装","name":"灰色摇粒绒外套","color":"灰色","material":"摇粒绒","desc":"摇粒绒保暖轻便，旅行首选"},
                "bottom": {"category":"下装","name":"黑色弹力登山裤","color":"黑色","material":"弹力面料","desc":"弹力登山裤，活动自如"},
                "outer": {"category":"外套","name":"军绿色冲锋衣","color":"军绿色","material":"防水面料","desc":"防风防雨，秋季多变天气"},
                "shoes": {"category":"鞋履","name":"棕色登山鞋","color":"棕色","material":"牛皮+橡胶","desc":"防滑登山鞋，户外必备"},
                "accessories": {"category":"配饰","name":"灰色针织冷帽","color":"灰色","material":"羊毛","desc":"保暖冷帽，秋季户外"},
                "reason":"秋季旅行推荐摇粒绒+冲锋衣的保暖组合，登山裤和登山鞋应对户外地形。"
            },
            "休闲": {
                "top": {"category":"上装","name":"米色圆领毛衣","color":"米色","material":"棉","desc":"米色毛衣温柔百搭"},
                "bottom": {"category":"下装","name":"深蓝色直筒牛仔裤","color":"深蓝色","material":"牛仔","desc":"经典深蓝牛仔，百搭"},
                "outer": {"category":"外套","name":"卡其色工装夹克","color":"卡其色","material":"棉","desc":"工装夹克，秋季休闲利器"},
                "shoes": {"category":"鞋履","name":"棕色帆布鞋","color":"棕色","material":"帆布","desc":"棕色帆布鞋，复古百搭"},
                "accessories": {"category":"配饰","name":"棕色毛线冷帽","color":"棕色","material":"羊毛","desc":"冷帽保暖，增添造型感"},
                "reason":"秋季休闲推荐米色毛衣+深蓝牛仔+卡其工装夹克，大地色系搭配温暖自然。"
            }
        },
        "冬季": {
            "面试": {
                "top": {"category":"上装","name":"白色棉质衬衫","color":"白色","material":"棉","desc":"经典白衬衫，面试标配"},
                "bottom": {"category":"下装","name":"黑色加厚西裤","color":"黑色","material":"羊毛","desc":"加厚保暖，修身版型"},
                "outer": {"category":"外套","name":"深灰色羊绒大衣","color":"深灰色","material":"羊绒","desc":"羊绒大衣保暖有型，专业沉稳"},
                "shoes": {"category":"鞋履","name":"黑色牛皮靴","color":"黑色","material":"牛皮","desc":"保暖皮靴，正式且实用"},
                "accessories": {"category":"配饰","name":"深蓝色羊绒围巾","color":"深蓝色","material":"羊绒","desc":"保暖围巾，增添层次"},
                "reason":"冬季面试推荐白衬衫+黑色西裤+深灰羊绒大衣，保暖专业，围巾增添层次。"
            },
            "约会": {
                "top": {"category":"上装","name":"奶咖色高领毛衣","color":"奶咖色","material":"羊绒","desc":"高领保暖优雅，奶咖色温柔"},
                "bottom": {"category":"下装","name":"深灰色羊毛阔腿裤","color":"深灰色","material":"羊毛","desc":"阔腿裤时尚有型，羊毛保暖"},
                "outer": {"category":"外套","name":"驼色长款羽绒服","color":"驼色","material":"涤纶+白鹅绒","desc":"驼色羽绒服，保暖且时尚"},
                "shoes": {"category":"鞋履","name":"棕色短靴","color":"棕色","material":"牛皮","desc":"短靴保暖时尚"},
                "accessories": {"category":"配饰","name":"米色针织围巾","color":"米色","material":"羊毛","desc":"围巾增添冬日氛围感"},
                "reason":"冬季约会推荐奶咖高领+深灰阔腿裤，温柔高级的暖色调搭配。"
            },
            "日常通勤": {
                "top": {"category":"上装","name":"黑色圆领毛衣","color":"黑色","material":"羊毛","desc":"黑色毛衣，百搭保暖"},
                "bottom": {"category":"下装","name":"深蓝色加绒牛仔裤","color":"深蓝色","material":"加绒牛仔","desc":"加绒保暖，深蓝经典"},
                "outer": {"category":"外套","name":"黑色羽绒服","color":"黑色","material":"涤纶+白鹅绒","desc":"轻便保暖，黑色百搭"},
                "shoes": {"category":"鞋履","name":"黑色加绒雪地靴","color":"黑色","material":"人造毛+橡胶","desc":"加绒保暖，防滑鞋底"},
                "accessories": {"category":"配饰","name":"黑色针织冷帽","color":"黑色","material":"羊毛","desc":"冷帽保暖，冬季标配"},
                "reason":"冬季日常以保暖为重，黑色毛衣+加绒牛仔+羽绒服实用百搭。"
            },
            "婚礼": {
                "top": {"category":"上装","name":"酒红色粗花呢西装","color":"酒红色","material":"粗花呢","desc":"酒红喜庆典雅，粗花呢有质感"},
                "bottom": {"category":"下装","name":"黑色羊毛西裤","color":"黑色","material":"羊毛","desc":"保暖羊毛，黑色百搭正式"},
                "outer": {"category":"外套","name":"酒红色粗花呢西装","color":"酒红色","material":"粗花呢","desc":"（与上装一体）"},
                "shoes": {"category":"鞋履","name":"黑色牛皮切尔西靴","color":"黑色","material":"牛皮","desc":"切尔西靴保暖有型"},
                "accessories": {"category":"配饰","name":"酒红色真丝领带","color":"酒红色","material":"真丝","desc":"领带与西装同色系"},
                "reason":"冬季婚礼推荐酒红粗花呢西装，喜庆且保暖，黑色西裤+切尔西靴庄重温暖。"
            },
            "派对": {
                "top": {"category":"上装","name":"银色亮片毛衣","color":"银色","material":"混纺","desc":"亮片元素，派对闪耀"},
                "bottom": {"category":"下装","name":"黑色修身皮裤","color":"黑色","material":"皮革","desc":"皮裤酷感十足"},
                "outer": {"category":"外套","name":"黑色长款羊毛大衣","color":"黑色","material":"羊毛","desc":"长款大衣，气场全开"},
                "shoes": {"category":"鞋履","name":"黑色高跟短靴","color":"黑色","material":"牛皮","desc":"短靴增添气场"},
                "accessories": {"category":"配饰","name":"银色耳环","color":"银色","material":"925银","desc":"银色耳环，与毛衣呼应"},
                "reason":"冬季派对推荐银色亮片毛衣+黑色皮裤+长款大衣，闪耀又酷飒。"
            },
            "旅行": {
                "top": {"category":"上装","name":"红色摇粒绒外套","color":"红色","material":"摇粒绒","desc":"红色摇粒绒，保暖且醒目"},
                "bottom": {"category":"下装","name":"黑色加绒运动裤","color":"黑色","material":"加绒面料","desc":"加绒运动裤，保暖舒适"},
                "outer": {"category":"外套","name":"黑色长款羽绒服","color":"黑色","material":"涤纶+白鹅绒","desc":"长款羽绒服，全面保暖"},
                "shoes": {"category":"鞋履","name":"棕色防滑雪地靴","color":"棕色","material":"牛皮+橡胶","desc":"防滑雪地靴，安全出行"},
                "accessories": {"category":"配饰","name":"红色针织冷帽","color":"红色","material":"羊毛","desc":"红色冷帽，与外套呼应"},
                "reason":"冬季旅行推荐红色摇粒绒+黑色羽绒服的保暖组合，防滑雪地靴确保安全。"
            },
            "休闲": {
                "top": {"category":"上装","name":"灰色连帽卫衣","color":"灰色","material":"棉混纺","desc":"灰色卫衣，冬季休闲首选"},
                "bottom": {"category":"下装","name":"黑色加绒卫裤","color":"黑色","material":"加绒棉","desc":"加绒卫裤，保暖舒适"},
                "outer": {"category":"外套","name":"黑色短款羽绒服","color":"黑色","material":"涤纶+白鹅绒","desc":"短款羽绒服，轻便保暖"},
                "shoes": {"category":"鞋履","name":"白色加绒运动鞋","color":"白色","material":"加绒网布+橡胶","desc":"加绒运动鞋，保暖舒适"},
                "accessories": {"category":"配饰","name":"灰色针织冷帽","color":"灰色","material":"羊毛","desc":"冷帽与卫衣同色系"},
                "reason":"冬季休闲推荐灰色卫衣+加绒卫裤+短款羽绒服，舒适保暖的居家休闲搭配。"
            }
        }
    }

    DEFAULT = {
        "top": {"category":"上装","name":"简约纯色衬衫","color":"白色","material":"棉","desc":"经典百搭单品"},
        "bottom": {"category":"下装","name":"修身休闲裤","color":"深灰色","material":"棉混纺","desc":"修身版型，百搭"},
        "outer": {"category":"外套","name":"休闲夹克","color":"深蓝色","material":"棉","desc":"休闲百搭外套"},
        "shoes": {"category":"鞋履","name":"休闲运动鞋","color":"白色","material":"网布+橡胶","desc":"舒适百搭运动鞋"},
        "accessories": {"category":"配饰","name":"简约手表","color":"银色","material":"不锈钢","desc":"简约百搭手表"},
        "reason":"根据您的画像和情景，推荐简约百搭的穿搭方案。"
    }

    def get_season(self, date_str):
        try:
            m = int(date_str[5:7])
            if m in (3,4,5): return "春季"
            if m in (6,7,8): return "夏季"
            if m in (9,10,11): return "秋季"
            return "冬季"
        except:
            return "秋季"

    def compose(self, profile, weather, occasion):
        season = self.get_season(datetime.now().strftime("%Y-%m-%d"))
        season_data = self.OUTFITS.get(season, self.OUTFITS["秋季"])
        outfit = season_data.get(occasion, self.DEFAULT)
        outfit = copy.deepcopy(outfit)
        outfit = self._personalize(outfit, profile, weather)
        return {
            "season": season,
            "occasion": occasion,
            "weather_advice": weather.get("advice", "") if weather else "",
            "items": [outfit["top"], outfit["bottom"], outfit["outer"], outfit["shoes"], outfit["accessories"]],
            "reason": outfit["reason"]
        }

    def _personalize(self, outfit, profile, weather):
        if not profile:
            return outfit
        items = [outfit["top"], outfit["bottom"], outfit["outer"], outfit["shoes"], outfit["accessories"]]
        pref_color = profile.get("color_preference", "")
        if pref_color:
            colors = [c.strip() for c in pref_color.split(",") if c.strip()]
            if colors:
                items[0]["color"] = colors[0]
                color_text = "、".join(colors)
                items[0]["desc"] += f"（融入您偏爱的{color_text}色调）"
        lucky = profile.get("lucky_color", "")
        if lucky:
            lucky_colors = [c.strip() for c in lucky.split(",") if c.strip()]
            if lucky_colors:
                items[4]["color"] = lucky_colors[0]
                lucky_text = "、".join(lucky_colors)
                items[4]["desc"] += f"（点缀幸运色{lucky_text}）"
        fabric = profile.get("preferred_fabric", "")
        if fabric:
            for it in items:
                if fabric in it["material"] or it["material"] in fabric:
                    it["desc"] += f"（含您偏好的{fabric}面料）"
        csize = profile.get("clothing_size", "")
        ssize = profile.get("shoe_size", "")
        if csize:
            items[0]["desc"] += f" | 建议尺码：{csize}"
        if ssize:
            items[3]["desc"] += f" | 建议尺码：{ssize}"
        temp = weather.get("temp", 20) if weather else 20
        if temp <= 5 and outfit["outer"]["material"] != "涤纶+白鹅绒":
            outfit["reason"] += " 冬季寒冷，建议在推荐基础上增加保暖内衣。"
        elif temp >= 28:
            outfit["reason"] += " 气温较高，建议省略外套，以短袖为主。"
        body = profile.get("body_shape") or ""
        if "宽肩" in body:
            outfit["reason"] += " 针对宽肩体型，推荐V领或插肩袖设计。"
        elif "窄肩" in body:
            outfit["reason"] += " 针对窄肩体型，推荐垫肩或落肩设计。"
        outfit["reason"] += f" 已根据您{profile.get('city','')}的天气和{profile.get('daily_style','休闲')}风格进行个性化调整。"
        return outfit
