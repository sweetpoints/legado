#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Legado 书源兼容性扫描器
=======================

用途：评估"把书源规则引擎迁到非 JVM 平台（iOS）"时的实际工作量与风险。

为什么要这个工具：
    Legado 的规则引擎依赖三类 JVM 专属能力——JsoupXpath（XPath）、Rhino（JS 书源，
    含 Java 互操作）、JsonPath。其中 JsoupXpath 在 Kotlin/Native 上无法移植，
    Rhino 没有 iOS 实现。因此"存量书源有多少会失效"是跨平台路线的 go/no-go 判据，
    而这个问题只能靠统计真实书源库来回答。

分类逻辑来源（不是猜的）：
    严格对齐 app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeRule.kt
      - JS 标记：AppPattern.JS_PATTERN  = <js>...</js> | @js:...
      - WebJs   ：AppPattern.WebJS_PATTERN = @webjs:...
      - SourceRule.init() 的 when 分支顺序（顺序很关键，见 classify_segment）

用法：
    python3 tools/source-compat-scan.py 书源1.json 书源2.json ... [--out 报告.md]

    输入可以是：
      * 书源数组            [{...}, {...}]
      * 单个书源对象        {...}
      * 带包装的对象        {"bookSources": [...]} 等

作者备注：字段清单取自 data/entities/BookSource.kt 与 data/entities/rule/*.kt。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter, defaultdict

# ---------------------------------------------------------------------------
# 规则字段清单（取自 BookSource.kt / rule/*.kt）
# ---------------------------------------------------------------------------

# 值本身是「规则字符串」，走 splitSourceRule 解析
RULE_FIELDS = {
    "ruleSearch": [
        "checkKeyWord", "bookList", "name", "author", "intro", "kind",
        "lastChapter", "updateTime", "bookUrl", "coverUrl", "wordCount",
    ],
    "ruleExplore": [
        "bookList", "name", "author", "intro", "kind",
        "lastChapter", "updateTime", "bookUrl", "coverUrl", "wordCount",
    ],
    "ruleBookInfo": [
        "init", "name", "author", "intro", "kind", "lastChapter", "updateTime",
        "coverUrl", "tocUrl", "wordCount", "canReName", "downloadUrls",
    ],
    "ruleToc": [
        "chapterList", "chapterName", "chapterUrl", "isVolume", "isVip",
        "isPay", "updateTime", "nextTocUrl",
    ],
    "ruleContent": [
        "content", "subContent", "title", "nextContentUrl",
        "imageStyle", "imageDecode", "payAction", "contentBatch", "maxBatchSize",
    ],
    "ruleReview": [
        "reviewUrl", "avatarRule", "contentRule", "postTimeRule",
        "reviewQuoteUrl", "voteUpUrl", "voteDownUrl", "postReviewUrl",
        "postQuoteUrl", "deleteUrl", "reviewSummaryUrl", "summaryListRule",
        "summaryParagraphIndexRule", "summaryParagraphDataRule", "summaryCountRule",
        "reviewDetailUrl", "reviewDetailNextPageUrl", "detailListRule",
        "detailIdRule", "detailAvatarRule", "detailNameRule", "detailBadgeRule",
        "detailContentRule", "replyListRule", "replyIdRule", "replyAvatarRule",
        "replyNameRule", "replyBadgeRule", "replyContentRule",
    ],
}

# 值本身是 JS 源码（不是规则字符串，不经过 splitSourceRule）
RAW_JS_FIELDS = [
    "loginCheckJs", "coverDecodeJs", "mainJs",
    ("ruleToc", "preUpdateJs"),
    ("ruleToc", "formatJs"),
    ("ruleContent", "webJs"),      # 在 WebView 内执行
    ("ruleContent", "callBackJs"),
]

# 正则字段（不是选择器规则）
REGEX_FIELDS = [
    "bookUrlPattern",
    ("ruleContent", "sourceRegex"),
    ("ruleContent", "replaceRegex"),
]

# URL 字段：可能内嵌 JS / 规则 / 模板变量
# 注意不含 loginUrl —— 它只是交给浏览器打开的地址，不经过规则引擎
URL_FIELDS = ["searchUrl"]

# ---------------------------------------------------------------------------
# 模式与正则（对齐 AppPattern 与 AnalyzeRule）
# ---------------------------------------------------------------------------

JS_PATTERN = re.compile(r"<js>([\w\W]*?)</js>|@js:([\w\W]*)", re.IGNORECASE)
WEBJS_PATTERN = re.compile(r"@webjs:([\w\W]{5,})", re.IGNORECASE)
# AnalyzeRule 的 evalPattern：@get:{...} 或 {{...}}（后者是内嵌 JS 表达式）
EVAL_PATTERN = re.compile(r"@get:\{[^}]+?\}|\{\{[\w\W]*?\}\}", re.IGNORECASE)
TEMPLATE_JS = re.compile(r"\{\{[\w\W]*?\}\}")
# {{page}} / {{key}} 这类**简单变量替换**不需要 JS 引擎，只是取值；
# 只有 {{java.ajax(...)}} 这种含运算/调用的才算真正的 JS 表达式。
SIMPLE_TEMPLATE = re.compile(r"^[A-Za-z_$][\w$]*$")

# JS 里「无法在 QuickJS / JavaScriptCore 上直接表达」的 Java 互操作语法
#
# 注意区分两类写法（这是本工具最容易出错的地方）：
#   java.ajax(...)          -> 宿主方法调用，可移植（只需重建绑定层），不能算硬阻塞
#   java.util.Base64        -> 直接引用 Java 类，硬阻塞
# 因此不能简单地匹配 java\.\w+ ——那会把前者一并吞掉。
# 这里按 Java 命名约定（包名小写、类名首字母大写）来区分。
_JAVA_CLASS = r"\bjava\.(?:[a-z][\w$]*\.)+[A-Z][\w$]*"
HARD_JS_PATTERNS = [
    (re.compile(r"\bPackages\s*\."), "Packages.*"),
    (re.compile(r"\bJavaImporter\s*\("), "JavaImporter(...)"),
    (re.compile(r"\bJava\.type\s*\("), "Java.type(...)"),
    (re.compile(r"\bJava\.extend\s*\("), "Java.extend(...)"),
    (re.compile(r"\bJavaAdapter\b"), "JavaAdapter"),
    (re.compile(r"\bimportPackage\s*\("), "importPackage(...)"),
    (re.compile(r"\bimportClass\s*\("), "importClass(...)"),
    (re.compile(r"\bnew\s+java\.[\w.$]+"), "new java.*"),
    (re.compile(_JAVA_CLASS), "java.<包>.<类> 直接引用"),
    (re.compile(r"\bjavax\.[\w.$]+"), "javax.*"),
]

# 宿主对象上的方法调用：java.xxx(...)。这些「可移植」——前提是重写绑定层。
HOST_METHOD = re.compile(r"\bjava\.([A-Za-z_$][\w$]*)\s*\(")
# 其它由宿主注入的绑定对象（AnalyzeRule.AnalyzeUrl 的 bindings）
HOST_BINDINGS = [
    "source", "book", "chapter", "chapters", "cookie", "cache", "result",
    "baseUrl", "src", "title", "nextChapterUrl", "rssArticle", "page",
    "infoMap", "key", "speakText", "speakSpeed", "paraIndex", "paraData",
]


# ---------------------------------------------------------------------------
# 规则解析（对齐 AnalyzeRule.splitSourceRule + SourceRule.init）
# ---------------------------------------------------------------------------

def split_rule(rule: str) -> list[tuple[str, str]]:
    """把规则串拆成 [(文本, 模式)]，模式 ∈ {Js, WebJs, _raw_}。

    _raw_ 表示尚未判定 CSS/XPath/Json 的普通片段，由 classify_segment 判定。
    """
    segments: list[tuple[str, str]] = []
    pos = 0
    for m in JS_PATTERN.finditer(rule):
        if m.start() > pos:
            text = rule[pos:m.start()].strip()
            if text:
                segments.append((text, "_raw_"))
        js = m.group(2) if m.group(2) is not None else m.group(1)
        segments.append((js or "", "Js"))
        pos = m.end()
    rest = rule[pos:]
    # WebJs 只在剩余部分里找（与 Kotlin 实现的实际效果一致：@js: 会吞掉后文）
    wpos = 0
    for m in WEBJS_PATTERN.finditer(rest):
        if m.start() > wpos:
            text = rest[wpos:m.start()].strip()
            if text:
                segments.append((text, "_raw_"))
        segments.append((m.group(1), "WebJs"))
        wpos = m.end()
    tail = rest[wpos:].strip()
    if tail:
        segments.append((tail, "_raw_"))
    return segments


def classify_segment(seg: str, is_json: bool) -> str:
    """判定一个普通片段的模式。分支顺序严格对齐 SourceRule.init()。

    注意 isJSON 的优先级高于「/ 开头即 XPath」——它是运行时由响应内容决定的，
    静态分析无法得知，因此本工具默认按 is_json=False（HTML）统计，
    并把这一假设写进报告的局限性里。
    """
    s = seg
    if s.lower().startswith("@css:"):
        return "CSS"
    if s.startswith("@@"):
        return "CSS(转义)"
    if s.lower().startswith("@xpath:"):
        return "XPath"
    if s.lower().startswith("@json:"):
        return "JSONPath"
    if is_json or s.startswith("$.") or s.startswith("$["):
        return "JSONPath"
    if s.startswith("/"):
        # AnalyzeRule 原注释：「XPath特征很明显,无需配置单独的识别标头」
        return "XPath"
    return "CSS"


def analyze_js(js: str) -> dict:
    """分析一段 JS 的可移植性。"""
    hard = []
    for pat, label in HARD_JS_PATTERNS:
        if pat.search(js):
            hard.append(label)
    host_methods = HOST_METHOD.findall(js)
    bindings = [b for b in HOST_BINDINGS if re.search(r"\b" + re.escape(b) + r"\b", js)]
    return {
        "length": len(js),
        "hard": hard,
        "host_methods": host_methods,
        "bindings": bindings,
    }


def scan_rule_string(rule: str, is_json: bool) -> dict:
    """扫描一条规则串，返回其模式分布与 JS 特征。"""
    modes = Counter()
    js_blocks: list[str] = []
    has_regex_replace = "##" in rule

    for text, mode in split_rule(rule):
        if mode in ("Js", "WebJs"):
            modes["WebJs" if mode == "WebJs" else "JS"] += 1
            js_blocks.append(text)
        else:
            m = classify_segment(text, is_json)
            modes[m] += 1
            # 片段里可能还有 {{ }} 模板：区分「简单取值」和「真 JS 表达式」
            for tm in TEMPLATE_JS.finditer(text):
                inner = tm.group(0)[2:-2].strip()
                if SIMPLE_TEMPLATE.match(inner):
                    modes["模板变量"] += 1
                else:
                    modes["模板表达式(JS)"] += 1
                    js_blocks.append(inner)
    return {
        "modes": modes,
        "js_blocks": js_blocks,
        "has_regex_replace": has_regex_replace,
    }


def explore_targets(raw: str) -> list[tuple[str, str, bool]]:
    """解析 exploreUrl。

    exploreUrl 是 ExploreKind 数组的 JSON 字符串：
        [{"title":"分类","url":"...{{page}}.html","type":"url",
          "action":"...","chars":[...],"style":{...}}]
    其中**只有 url 是规则**、action 是点击时执行的 JS；
    title / chars / default / viewName / style 都是 UI 值。
    早期版本把整串递归拆出来统计，会让标题和样式值污染分母——务必只取 url/action。
    """
    out: list[tuple[str, str, bool]] = []
    try:
        data = json.loads(raw)
    except Exception:
        # 不是规范 JSON（App 也会给出格式不规范提示），整串当规则处理
        return [("exploreUrl", raw, False)]
    if not isinstance(data, list):
        return [("exploreUrl", raw, False)]
    for i, item in enumerate(data):
        if not isinstance(item, dict):
            continue
        u = item.get("url")
        if isinstance(u, str) and u.strip():
            out.append((f"exploreUrl[{i}].url", u.strip(), False))
        a = item.get("action")
        if isinstance(a, str) and a.strip():
            out.append((f"exploreUrl[{i}].action", a.strip(), True))
    return out


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def load_sources(paths: list[str]) -> tuple[list[dict], dict]:
    raw: list[dict] = []
    per_file = {}
    for p in paths:
        with open(p, encoding="utf-8") as f:
            data = json.load(f)
        items = []
        if isinstance(data, list):
            items = data
        elif isinstance(data, dict):
            if "bookSourceUrl" in data:
                items = [data]
            else:
                for key in ("bookSources", "sources", "data", "items"):
                    if isinstance(data.get(key), list):
                        items = data[key]
                        break
        items = [x for x in items if isinstance(x, dict) and "bookSourceUrl" in x]
        per_file[os.path.basename(p)] = len(items)
        raw.extend(items)

    seen, deduped, dup = set(), [], 0
    for s in raw:
        key = (s.get("bookSourceUrl") or "").strip()
        if not key:
            continue
        if key in seen:
            dup += 1
            continue
        seen.add(key)
        deduped.append(s)
    return deduped, {"per_file": per_file, "raw": len(raw), "deduped": len(deduped), "duplicates": dup}


def scan(sources: list[dict], is_json: bool = False) -> dict:
    mode_counts = Counter()            # 规则片段级别的模式计数
    field_mode = defaultdict(Counter)  # 字段 -> 模式
    js_total = 0
    js_with_hard = 0
    hard_counter = Counter()
    host_method_counter = Counter()
    binding_counter = Counter()
    src_has_xpath = 0
    src_has_hard_js = 0
    src_has_any_js = 0
    src_clean = 0                  # 无 XPath 且无硬 Java 互操作
    src_no_js = 0                  # 在上者基础上，还完全不含 JS
    hard_sources: list[tuple[str, str]] = []
    xpath_sources: list[tuple[str, str]] = []

    for s in sources:
        name = s.get("bookSourceName") or s.get("bookSourceUrl") or "?"
        url = s.get("bookSourceUrl") or ""
        s_modes = Counter()
        s_hard = set()
        s_js = 0

        def visit(label: str, rule: str, raw_js: bool = False) -> None:
            nonlocal js_total, js_with_hard, s_js
            if raw_js:
                s_modes["JS"] += 1
                mode_counts["JS"] += 1
                field_mode[label]["JS"] += 1
                s_js += 1
                js_total += 1
                info = analyze_js(rule)
                if info["hard"]:
                    js_with_hard += 1
                    for h in info["hard"]:
                        hard_counter[h] += 1
                    s_hard.update(info["hard"])
                for m in info["host_methods"]:
                    host_method_counter[m] += 1
                for b in info["bindings"]:
                    binding_counter[b] += 1
                return

            res = scan_rule_string(rule, is_json)
            for mode, n in res["modes"].items():
                mode_counts[mode] += n
                field_mode[label][mode] += n
                s_modes[mode] += n
            # 「含 JS 代码」只算真正的 JS：@js/<js>/@webjs/复杂模板表达式；
            # 单纯的 {{page}} 不算（它不需要 JS 引擎）。
            if (res["modes"].get("JS") or res["modes"].get("WebJs")
                    or res["modes"].get("模板表达式(JS)")):
                s_js += 1
            for block in res["js_blocks"]:
                js_total += 1
                info = analyze_js(block)
                if info["hard"]:
                    js_with_hard += 1
                    for h in info["hard"]:
                        hard_counter[h] += 1
                    s_hard.update(info["hard"])
                for m in info["host_methods"]:
                    host_method_counter[m] += 1
                for b in info["bindings"]:
                    binding_counter[b] += 1

        # 规则字段
        for group, fields in RULE_FIELDS.items():
            obj = s.get(group)
            if not isinstance(obj, dict):
                continue
            for f in fields:
                v = obj.get(f)
                if isinstance(v, str) and v.strip():
                    visit(f"{group}.{f}", v.strip())

        # 原生 JS 字段
        for f in RAW_JS_FIELDS:
            if isinstance(f, tuple):
                obj = s.get(f[0])
                v = obj.get(f[1]) if isinstance(obj, dict) else None
                label = f"{f[0]}.{f[1]}"
            else:
                v = s.get(f)
                label = f
            if isinstance(v, str) and v.strip():
                visit(label, v.strip(), raw_js=True)

        # 正则字段（只统计存在性，不做模式分类）
        for f in REGEX_FIELDS:
            if isinstance(f, tuple):
                obj = s.get(f[0])
                v = obj.get(f[1]) if isinstance(obj, dict) else None
            else:
                v = s.get(f)
            if isinstance(v, str) and v.strip():
                mode_counts["Regex(字段)"] += 1

        # URL 字段（searchUrl 整串就是规则）
        for f in URL_FIELDS:
            v = s.get(f)
            if isinstance(v, str) and v.strip():
                visit(f, v.strip())

        # exploreUrl：只取 url / action（见 explore_targets 的说明）
        ev = s.get("exploreUrl")
        if isinstance(ev, str) and ev.strip():
            for label, text, is_raw_js in explore_targets(ev.strip()):
                visit(label, text, raw_js=is_raw_js)

        if s_modes.get("XPath"):
            src_has_xpath += 1
            if len(xpath_sources) < 400:
                xpath_sources.append((name, url))
        if s_hard:
            src_has_hard_js += 1
            if len(hard_sources) < 400:
                hard_sources.append((name, ", ".join(sorted(s_hard))))
        if s_js:
            src_has_any_js += 1
        if not s_modes.get("XPath") and not s_hard:
            src_clean += 1
            if not s_js:
                src_no_js += 1

    return {
        "mode_counts": mode_counts,
        "field_mode": field_mode,
        "js_total": js_total,
        "js_with_hard": js_with_hard,
        "hard_counter": hard_counter,
        "host_method_counter": host_method_counter,
        "binding_counter": binding_counter,
        "src_total": len(sources),
        "src_has_xpath": src_has_xpath,
        "src_has_hard_js": src_has_hard_js,
        "src_has_any_js": src_has_any_js,
        "src_clean": src_clean,
        "src_no_js": src_no_js,
        "xpath_sources": xpath_sources,
        "hard_sources": hard_sources,
    }


def pct(a: int, b: int) -> str:
    return f"{a / b * 100:.1f}%" if b else "-"


def build_report(r: dict, meta: dict, args) -> str:
    total = r["src_total"]
    lines: list[str] = []
    A = lines.append

    A("# 书源兼容性扫描报告（跨平台可行性判据）")
    A("")
    A("> 由 `tools/source-compat-scan.py` 生成。分类逻辑对齐 "
      "`AnalyzeRule.splitSourceRule` / `SourceRule.init`。")
    A("")

    A("## 1. 数据来源")
    A("")
    A("| 文件 | 书源数 |")
    A("| --- | --- |")
    for k, v in meta["per_file"].items():
        A(f"| `{k}` | {v} |")
    A("")
    A(f"- 原始条数：**{meta['raw']}**")
    A(f"- 按 `bookSourceUrl` 去重后：**{meta['deduped']}**（重复 {meta['duplicates']}）")
    A("")

    A("## 2. 规则类型分布（片段级）")
    A("")
    mc = r["mode_counts"]
    tot_seg = sum(v for k, v in mc.items() if k != "Regex(字段)")
    A("| 规则类型 | 片段数 | 占比 | iOS 端影响 |")
    A("| --- | --- | --- | --- |")
    impact = {
        "CSS": "✅ 可移植（Ksoup）",
        "CSS(转义)": "✅ 无解析",
        "XPath": "❌ **JsoupXpath 无法移植，需改写或降级**",
        "JSONPath": "⚠️ 需换 JSONPath 实现（语义待核对）",
        "JS": "⚠️ 需换 JS 引擎 + **重写宿主绑定层**",
        "WebJs": "⚠️ 依赖 WebView，iOS 需 WKWebView 实现",
        "模板表达式(JS)": "⚠️ 需 JS 引擎（可能含宿主调用）",
        "模板变量": "✅ 仅取值，**不需要** JS 引擎",
        "Regex(字段)": "✅ 正则，可移植",
    }
    for k, v in mc.most_common():
        A(f"| {k} | {v} | {pct(v, tot_seg)} | {impact.get(k, '')} |")
    A(f"| **合计** | **{tot_seg}** | | |")
    A("")

    A("## 3. 书源级影响面")
    A("")
    A("| 指标 | 书源数 | 占比 |")
    A("| --- | --- | --- |")
    A(f"| 总数 | {total} | 100% |")
    A(f"| 含 XPath 规则 | {r['src_has_xpath']} | {pct(r['src_has_xpath'], total)} |")
    A(f"| 含 JS 代码（`@js`/`<js>`/`@webjs`/复杂 `{{}}`） | {r['src_has_any_js']} | "
      f"{pct(r['src_has_any_js'], total)} |")
    A(f"| 含**硬 Java 互操作** JS | {r['src_has_hard_js']} | {pct(r['src_has_hard_js'], total)} |")
    A("")
    A("### 3.1 可移植性分层")
    A("")
    A("| 层级 | 判定条件 | 书源数 | 占比 | 说明 |")
    A("| --- | --- | --- | --- | --- |")
    A(f"| **A** | 无 XPath、无硬互操作、**无 JS** | {r['src_no_js']} | "
      f"{pct(r['src_no_js'], total)} | 纯 CSS/JSONPath，换解析库即可 |")
    A(f"| **B** | 无 XPath、无硬互操作，但有 JS | "
      f"{r['src_clean'] - r['src_no_js']} | {pct(r['src_clean'] - r['src_no_js'], total)} | "
      f"需 JS 引擎 + 重建宿主层，但语法无阻塞 |")
    A(f"| **C** | 含 XPath（可能同时有 JS） | {r['src_has_xpath']} | "
      f"{pct(r['src_has_xpath'], total)} | 需把 XPath 改写为 CSS 或自研 XPath 子集 |")
    A(f"| **D** | 含硬 Java 互操作 | {r['src_has_hard_js']} | "
      f"{pct(r['src_has_hard_js'], total)} | 对应 JS 段在 iOS 上**无法直接运行** |")
    A("")
    A("> A/B/C/D 会重叠（一个书源可同时命中多项），因此占比之和大于 100%。")
    A("")
    A(f"**结论**：`A + B` = **{r['src_clean']}（{pct(r['src_clean'], total)}）** "
      f"的书源**不含 XPath**，其失效风险只来自 JS 引擎与宿主层；"
      f"剩下 **{r['src_has_xpath']}（{pct(r['src_has_xpath'], total)}）** 含 XPath，是 JsoupXpath 缺失的直接受害面。")
    A("")

    A("## 4. JS 可移植性细分")
    A("")
    A(f"- JS 代码块总数（`@js` / `<js>` / `@webjs` / 复杂 `{{}}`）：**{r['js_total']}**")
    A(f"- 其中含硬 Java 互操作：**{r['js_with_hard']}**（{pct(r['js_with_hard'], r['js_total'])}）")
    A("")
    A("> 关键区分：`java.ajax(...)` 这类**宿主方法调用**与 `Packages.java.util.Base64` 这类"
      "**直接引用 Java 类**，跨平台代价完全不同。前者只需在目标平台重建等价方法，"
      "后者在 QuickJS / JavaScriptCore 中**没有对应语法**。")
    A("")
    if r["hard_counter"]:
        A("### 4.1 硬阻塞语法（在 QuickJS / JavaScriptCore 中无对应物）")
        A("")
        A("| 语法 | 出现次数 |")
        A("| --- | --- |")
        for k, v in r["hard_counter"].most_common():
            A(f"| `{k}` | {v} |")
        A("")
    if r["host_method_counter"]:
        A("### 4.2 需要重建的宿主 API（`java.*` 调用，按频次）")
        A("")
        A("> 这些**不是** Rhino 特有语法，只是调用绑定到 JS 的 Kotlin 对象。"
          "理论上可移植，但需要在目标平台重新实现同等语义的方法——下表即为工作量清单。")
        A("")
        A("| 方法 | 调用次数 |")
        A("| --- | --- |")
        for k, v in r["host_method_counter"].most_common(40):
            A(f"| `java.{k}(...)` | {v} |")
        A(f"| **不同方法数** | **{len(r['host_method_counter'])}** |")
        A("")
    if r["binding_counter"]:
        A("### 4.3 被引用的宿主绑定对象")
        A("")
        A("| 绑定 | 引用次数 |")
        A("| --- | --- |")
        for k, v in r["binding_counter"].most_common():
            A(f"| `{k}` | {v} |")
        A("")

    A("## 5. 字段级分布（前 25）")
    A("")
    fm = r["field_mode"]
    flat = sorted(fm.items(), key=lambda kv: -sum(kv[1].values()))[:25]
    keys = ["CSS", "XPath", "JSONPath", "JS", "WebJs"]
    A("| 字段 | " + " | ".join(keys) + " |")
    A("| --- | " + " | ".join("---" for _ in keys) + " |")
    for field, c in flat:
        A(f"| `{field}` | " + " | ".join(str(c.get(k, 0)) for k in keys) + " |")
    A("")

    if r["hard_sources"]:
        A("## 6. 含硬互操作的书源（样例，最多 400）")
        A("")
        A("| 书源 | 命中语法 |")
        A("| --- | --- |")
        for name, hits in r["hard_sources"][:60]:
            A(f"| {name} | {hits} |")
        A(f"\n（共 {r['src_has_hard_js']} 个，此处仅列前 {min(60, len(r['hard_sources']))} 个）")
        A("")

    A("## 7. 局限性与假设（务必阅读）")
    A("")
    A("1. **`isJSON` 无法静态判定**。App 在 `setContent()` 里根据**实际响应内容**是否可解析为 JSON "
      "来决定 `isJSON`，而它的判断优先级**高于**「`/` 开头即 XPath」。"
      "本报告默认按 `isJSON=False`（即响应为 HTML）统计——这是对 XPath 暴露量的**保守上界**。"
      "若某书源实际走 JSON API，其无前缀规则会被判为 JSONPath 而非 CSS/XPath。")
    A("2. **未执行任何规则**。本工具是纯静态分析，不联网、不验证选择器是否真的能取到内容。")
    A("3. **XPath 计数含误报可能**：`/` 开头也可能是被误写的 CSS，反之 CSS 里也可能出现 `/`。")
    A("4. **硬互操作清单是正则匹配**，可能漏掉动态构造类名的写法（如 `Packages[cls]`）。")
    A("5. 书源的 `enabled` 状态、权重、实际可用性均未参与统计——本报告统计的是**规则语法形态**。")
    A("")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description="Legado 书源兼容性扫描器")
    ap.add_argument("files", nargs="+", help="书源 JSON 文件（数组或单对象）")
    ap.add_argument("--out", help="报告输出路径（Markdown）")
    ap.add_argument("--json-out", help="原始统计输出路径（JSON）")
    ap.add_argument("--assume-json", action="store_true",
                    help="按 isJSON=True 统计（默认 False，即按 HTML 内容）")
    args = ap.parse_args()

    sources, meta = load_sources(args.files)
    if not sources:
        print("未找到任何书源，请检查输入文件格式。", file=sys.stderr)
        return 1

    result = scan(sources, is_json=args.assume_json)
    report = build_report(result, meta, args)

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(report)
        print(f"报告已写入 {args.out}")
    else:
        print(report)

    if args.json_out:
        serializable = {k: v for k, v in result.items()
                        if k not in ("xpath_sources", "hard_sources")}
        serializable["mode_counts"] = dict(result["mode_counts"])
        serializable["hard_counter"] = dict(result["hard_counter"])
        serializable["host_method_counter"] = dict(result["host_method_counter"])
        serializable["binding_counter"] = dict(result["binding_counter"])
        serializable["field_mode"] = {k: dict(v) for k, v in result["field_mode"].items()}
        serializable["meta"] = meta
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump(serializable, f, ensure_ascii=False, indent=2)
        print(f"统计已写入 {args.json_out}")

    # 控制台速览
    total = result["src_total"]
    print()
    print(f"书源总数（去重后）: {total}")
    print(f"含 XPath          : {result['src_has_xpath']} ({pct(result['src_has_xpath'], total)})")
    print(f"含硬 Java 互操作  : {result['src_has_hard_js']} ({pct(result['src_has_hard_js'], total)})")
    print(f"两者皆无          : {result['src_clean']} ({pct(result['src_clean'], total)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
