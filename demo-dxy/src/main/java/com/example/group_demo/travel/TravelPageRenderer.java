package com.example.group_demo.travel;

import com.example.group_demo.travel.TravelPlan.Budget;
import com.example.group_demo.travel.TravelPlan.BudgetItem;
import com.example.group_demo.travel.TravelPlan.DayPlan;
import com.example.group_demo.travel.TravelPlan.TimeSlot;
import org.springframework.stereotype.Component;

/**
 * 把结构化旅行方案渲染成可分享的 HTML 网页。
 * 所有来自 LLM 的文本都会先做 HTML 转义，避免注入。
 */
@Component
public class TravelPageRenderer {

    public String render(TravelPlan plan, String pageId, String heroSrc, String voiceSrc) {
        if (plan == null || plan.destination() == null || plan.destination().isBlank()) {
            throw new IllegalArgumentException("旅行方案缺少目的地");
        }
        StringBuilder html = new StringBuilder(8192);
        html.append("<!doctype html>\n")
            .append("<html lang=\"zh-CN\">\n")
            .append("<head>\n")
            .append("<meta charset=\"utf-8\">\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("<title>").append(escapeHtml(plan.destination())).append(" ")
            .append(plan.days()).append(" 日游方案</title>\n")
            .append("<style>\n").append(CSS).append("</style>\n")
            .append("</head>\n<body>\n");

        appendHero(html, plan, heroSrc);
        appendOverview(html, plan);
        appendDays(html, plan);
        appendBudget(html, plan);
        appendMustDos(html, plan);
        appendTips(html, plan);
        if (voiceSrc != null && !voiceSrc.isBlank()) {
            appendVoice(html, voiceSrc);
        }
        html.append("<footer>本方案由旅行规划 Agent 自动生成，出行前请以实时信息为准。</footer>\n")
            .append("</body>\n</html>\n");
        return html.toString();
    }

    private void appendHero(StringBuilder html, TravelPlan plan, String heroSrc) {
        html.append("<header class=\"hero\"");
        if (heroSrc != null && !heroSrc.isBlank()) {
            html.append(" style=\"background-image:url('").append(escapeHtml(heroSrc)).append("')\"");
        }
        html.append(">\n")
            .append("<div class=\"hero-scrim\">\n")
            .append("<div class=\"hero-inner\">\n")
            .append("<p class=\"eyebrow\">AI 旅行规划 Agent 自动生成</p>\n")
            .append("<h1>").append(escapeHtml(plan.destination())).append(" ")
            .append(plan.days()).append(" 日游</h1>\n");
        if (!plan.dates().isEmpty()) {
            html.append("<p class=\"hero-sub\">出行日期 ")
                .append(escapeHtml(String.join(" · ", plan.dates()))).append("</p>\n");
        }
        html.append("<div class=\"chips\">\n");
        if (plan.budget() != null && notBlank(plan.budget().total())) {
            html.append("<span class=\"chip\">预算约 ")
                .append(escapeHtml(money(plan.budget().total()))).append("</span>\n");
        }
        html.append("<span class=\"chip\">").append(plan.itinerary().size())
            .append(" 天行程</span>\n");
        if (plan.budget() != null && !plan.budget().items().isEmpty()) {
            html.append("<span class=\"chip\">").append(plan.budget().items().size())
                .append(" 项预算</span>\n");
        }
        html.append("</div>\n</div>\n</div>\n</header>\n");
    }

    private void appendOverview(StringBuilder html, TravelPlan plan) {
        html.append("<main>\n<section class=\"overview\">\n");
        html.append("<div class=\"stat\"><span class=\"stat-label\">目的地</span>")
            .append("<span class=\"stat-value\">").append(escapeHtml(plan.destination())).append("</span></div>\n");
        html.append("<div class=\"stat\"><span class=\"stat-label\">行程天数</span>")
            .append("<span class=\"stat-value\">").append(plan.days()).append(" 天</span></div>\n");
        String total = plan.budget() == null ? "" : plan.budget().total();
        html.append("<div class=\"stat\"><span class=\"stat-label\">预算</span>")
            .append("<span class=\"stat-value\">").append(escapeHtml(money(total)))
            .append("</span></div>\n");
        html.append("</section>\n");
    }

    private void appendDays(StringBuilder html, TravelPlan plan) {
        html.append("<section class=\"days\">\n<h2>每日行程</h2>\n");
        if (plan.itinerary().isEmpty()) {
            html.append("<p class=\"empty\">本次规划未能生成逐日行程。</p>\n");
        }
        for (DayPlan day : plan.itinerary()) {
            html.append("<article class=\"day-card\">\n")
                .append("<div class=\"day-head\">\n")
                .append("<span class=\"day-no\">DAY ").append(Math.max(1, day.day())).append("</span>\n")
                .append("<div class=\"day-title\">\n")
                .append("<h3>").append(escapeHtml(day.title())).append("</h3>\n");
            if (notBlank(day.weather())) {
                html.append("<p class=\"day-weather\">").append(escapeHtml(day.weather())).append("</p>\n");
            }
            html.append("</div>\n</div>\n");
            if (!day.schedule().isEmpty()) {
                html.append("<ol class=\"schedule\">\n");
                for (TimeSlot slot : day.schedule()) {
                    html.append("<li>\n<span class=\"time\">").append(escapeHtml(slot.time())).append("</span>\n")
                        .append("<span class=\"item\">").append(escapeHtml(slot.item())).append("</span>\n</li>\n");
                }
                html.append("</ol>\n");
            }
            boolean hasMeta = notBlank(day.meals()) || notBlank(day.hotel()) || notBlank(day.notes());
            if (hasMeta) {
                html.append("<dl class=\"day-meta\">\n");
                appendMeta(html, "餐饮", day.meals());
                appendMeta(html, "住宿", day.hotel());
                appendMeta(html, "提示", day.notes());
                html.append("</dl>\n");
            }
            html.append("</article>\n");
        }
        html.append("</section>\n");
    }

    private void appendMeta(StringBuilder html, String label, String value) {
        if (notBlank(value)) {
            html.append("<dt>").append(label).append("</dt><dd>")
                .append(escapeHtml(value)).append("</dd>\n");
        }
    }

    private void appendBudget(StringBuilder html, TravelPlan plan) {
        Budget budget = plan.budget();
        if (budget == null || budget.items().isEmpty()) {
            return;
        }
        html.append("<section class=\"budget\">\n<h2>预算明细</h2>\n")
            .append("<table>\n<thead><tr><th>项目</th><th>金额</th></tr></thead>\n<tbody>\n");
        for (BudgetItem item : budget.items()) {
            html.append("<tr><td>").append(escapeHtml(item.name())).append("</td><td>")
                .append(escapeHtml(money(item.amount()))).append("</td></tr>\n");
        }
        if (notBlank(budget.total())) {
            html.append("<tr class=\"total\"><td>总计</td><td>")
                .append(escapeHtml(money(budget.total()))).append("</td></tr>\n");
        }
        html.append("</tbody>\n</table>\n</section>\n");
    }

    private void appendMustDos(StringBuilder html, TravelPlan plan) {
        if (plan.mustDos().isEmpty()) {
            return;
        }
        html.append("<section class=\"mustdos\">\n<h2>必做事项</h2>\n<ul class=\"checklist\">\n");
        for (String item : plan.mustDos()) {
            html.append("<li><span class=\"check\"></span>")
                .append(escapeHtml(item)).append("</li>\n");
        }
        html.append("</ul>\n</section>\n");
    }

    private void appendTips(StringBuilder html, TravelPlan plan) {
        if (plan.tips().isEmpty()) {
            return;
        }
        html.append("<section class=\"tips\">\n<h2>出行提示</h2>\n<ol class=\"tip-list\">\n");
        for (String tip : plan.tips()) {
            html.append("<li>").append(escapeHtml(tip)).append("</li>\n");
        }
        html.append("</ol>\n</section>\n");
    }

    private void appendVoice(StringBuilder html, String voiceSrc) {
        html.append("<section class=\"voice\">\n<h2>语音摘要</h2>\n")
            .append("<audio controls preload=\"none\" src=\"")
            .append(escapeHtml(voiceSrc)).append("\">你的浏览器不支持音频播放。</audio>\n")
            .append("</section>\n");
    }

    static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }

    static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static String money(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isBlank()) {
            return "待确认";
        }
        return value.matches(".*[元¥$￥].*") ? value : value + " 元";
    }

    private static final String CSS = """
        :root {
          --ink: #172433;
          --muted: #5b6b7a;
          --paper: #f6f8fa;
          --line: #d9e2ea;
          --brand: #0f766e;
          --brand-dark: #0b5d56;
          --accent: #e05d44;
          --white: #ffffff;
        }
        * { box-sizing: border-box; }
        html, body { margin: 0; }
        body {
          color: var(--ink);
          font-family: "Segoe UI", system-ui, -apple-system, "Microsoft YaHei", sans-serif;
          background: var(--paper);
          letter-spacing: 0;
        }
        .hero {
          min-height: 360px;
          background: linear-gradient(135deg, #0f766e 0%, #123a4a 100%);
          background-size: cover;
          background-position: center;
        }
        .hero-scrim {
          min-height: 360px;
          display: flex;
          align-items: flex-end;
          background: linear-gradient(180deg, rgba(10, 26, 38, 0.08) 0%, rgba(10, 26, 38, 0.78) 100%);
        }
        .hero-inner {
          width: 100%;
          max-width: 1040px;
          margin: 0 auto;
          padding: 48px 20px 36px;
          color: var(--white);
        }
        .eyebrow {
          margin: 0 0 10px;
          font-size: 12px;
          font-weight: 600;
          text-transform: uppercase;
          color: #bde3de;
        }
        h1 {
          margin: 0;
          font-size: 44px;
          line-height: 1.15;
        }
        .hero-sub {
          margin: 12px 0 0;
          font-size: 16px;
          color: #e7f2f1;
        }
        .chips {
          display: flex;
          flex-wrap: wrap;
          gap: 8px;
          margin-top: 18px;
        }
        .chip {
          display: inline-flex;
          align-items: center;
          height: 32px;
          padding: 0 14px;
          border: 1px solid rgba(255, 255, 255, 0.32);
          border-radius: 999px;
          background: rgba(255, 255, 255, 0.14);
          font-size: 13px;
          font-weight: 600;
        }
        main {
          max-width: 1040px;
          margin: 0 auto;
          padding: 28px 20px 48px;
        }
        section { margin: 34px 0 0; }
        h2 {
          margin: 0 0 16px;
          font-size: 24px;
          line-height: 1.3;
          padding-bottom: 10px;
          border-bottom: 2px solid var(--line);
        }
        .overview {
          display: grid;
          grid-template-columns: repeat(3, 1fr);
          gap: 14px;
          margin-top: 0;
        }
        .stat {
          padding: 16px 18px;
          border: 1px solid var(--line);
          border-left: 4px solid var(--accent);
          border-radius: 8px;
          background: var(--white);
        }
        .stat-label {
          display: block;
          font-size: 12px;
          color: var(--muted);
          margin-bottom: 6px;
        }
        .stat-value {
          display: block;
          font-size: 20px;
          font-weight: 700;
        }
        .day-card {
          margin: 0 0 18px;
          padding: 22px;
          border: 1px solid var(--line);
          border-radius: 8px;
          background: var(--white);
        }
        .day-head {
          display: flex;
          align-items: flex-start;
          gap: 14px;
          margin-bottom: 16px;
        }
        .day-no {
          flex: 0 0 auto;
          display: inline-flex;
          align-items: center;
          justify-content: center;
          height: 42px;
          padding: 0 12px;
          border-radius: 8px;
          background: var(--brand);
          color: var(--white);
          font-size: 13px;
          font-weight: 700;
        }
        .day-title h3 {
          margin: 0;
          font-size: 20px;
          line-height: 1.3;
        }
        .day-weather {
          margin: 6px 0 0;
          font-size: 13px;
          color: var(--accent);
          font-weight: 600;
        }
        .schedule {
          list-style: none;
          margin: 0;
          padding: 0;
        }
        .schedule li {
          display: flex;
          gap: 14px;
          padding: 10px 0;
          border-top: 1px dashed var(--line);
        }
        .time {
          flex: 0 0 84px;
          font-weight: 700;
          color: var(--brand);
        }
        .item { line-height: 1.5; }
        .day-meta {
          display: grid;
          grid-template-columns: 64px 1fr;
          gap: 6px 12px;
          margin: 14px 0 0;
          padding: 12px 14px;
          border-radius: 8px;
          background: #f0f6f5;
        }
        .day-meta dt {
          font-size: 13px;
          font-weight: 700;
          color: var(--brand-dark);
        }
        .day-meta dd {
          margin: 0;
          font-size: 13px;
          line-height: 1.5;
        }
        table {
          width: 100%;
          border-collapse: collapse;
          background: var(--white);
        }
        th, td {
          padding: 12px 14px;
          border-bottom: 1px solid var(--line);
          text-align: left;
        }
        th {
          font-size: 13px;
          color: var(--muted);
          background: #eef4f4;
        }
        tr.total td {
          font-weight: 700;
          color: var(--brand-dark);
          border-bottom: 0;
        }
        .checklist {
          list-style: none;
          margin: 0;
          padding: 0;
          display: grid;
          grid-template-columns: repeat(2, minmax(0, 1fr));
          gap: 10px 18px;
        }
        .checklist li {
          display: flex;
          align-items: center;
          gap: 10px;
          padding: 12px 14px;
          border: 1px solid var(--line);
          border-radius: 8px;
          background: var(--white);
          line-height: 1.4;
        }
        .check {
          flex: 0 0 18px;
          width: 18px;
          height: 18px;
          border: 2px solid var(--brand);
          border-radius: 5px;
        }
        .tip-list {
          margin: 0;
          padding: 0 0 0 22px;
          counter-reset: tip;
        }
        .tip-list li {
          margin: 0 0 10px;
          padding-left: 8px;
          line-height: 1.6;
        }
        .voice {
          padding: 18px;
          border: 1px solid var(--line);
          border-radius: 8px;
          background: var(--white);
        }
        .voice h2 { border-bottom: 0; margin-bottom: 12px; }
        audio { width: 100%; }
        footer {
          max-width: 1040px;
          margin: 0 auto;
          padding: 20px;
          text-align: center;
          font-size: 12px;
          color: var(--muted);
        }
        .empty {
          color: var(--muted);
          padding: 18px;
          border: 1px dashed var(--line);
          border-radius: 8px;
          background: var(--white);
        }
        @media (max-width: 640px) {
          h1 { font-size: 32px; }
          .hero, .hero-scrim { min-height: 300px; }
          .overview { grid-template-columns: 1fr; }
          .checklist { grid-template-columns: 1fr; }
          .schedule li { flex-direction: column; gap: 4px; }
          .time { flex: none; }
        }
        """;
}
