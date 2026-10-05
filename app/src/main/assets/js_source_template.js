/**
 * JavaScript 单文件书源模板。
 * search、getChapters、getContent 为必需函数，getBookInfo 和 explore 为可选函数。
 * getReviewSummary 与 getReviewDetail 成对声明即可启用段评，getReviewReplies 可选用于回复分页。
 * loginUi 与 loginAction 成对声明即可启用动态登录界面。
 * config 保存脚本配置；source 与 sourceApi 是书源 JSON snapshot，不是 Room/Java 对象。
 * HTTP 调用返回 Promise，使用 async function 和 await java.ajax 取得正文。
 * V8 的 java 只提供已列明的兼容白名单；不提供 org/Packages 或任意 Java 类。
 * source.getLoginInfo/putLoginHeader 等对象方法、jsLib/CryptoJS 需要显式迁移。
 * book/chapter 与 HTTP 返回字符串均使用原生 JS String 语义。
 */

var config = {
    bookSourceUrl: "https://example.com",
    bookSourceName: "示例 JS 书源",
    bookSourceType: 0,
    bookSourceGroup: "",
    bookSourceComment: "",
    // 旧版表单登录示例: [{ name: "账号", type: "text" }, { name: "密码", type: "password" }]
    loginUi: [],
    // 发现分类支持 JSON 数组，或“名称::url”文本（换行或 && 分隔）。
    exploreUrl: [],
    lastUpdateTime: 0
};

// config.loginUi 非空时必须提供。
async function login() {
    // loginUi 配对契约保留，但旧书源实体的登录信息/头方法不能在 JSON snapshot 上调用。
    // 按所需登录能力完成迁移后替换本示例；不要依赖 source.getLoginInfo()。
    throw "engine_migration_required: 请迁移登录信息与请求头处理";
}

/**
 * 动态登录界面（可选）由 loginUi(state) 与 loginAction(action, state, form) 成对启用，
 * 不要同时填写 config.loginUi。支持 text、password、label、select 和 button 行；
 * loginAction 可返回 state、error、login、close 命令，完整示例见 JS 帮助。
 */

async function search(key, page) {
    var html = await java.ajax(config.bookSourceUrl + "/search?q=" + encodeURIComponent(key) + "&p=" + page);
    var books = [];
    // books.push({ name: "书名", bookUrl: "https://example.com/book/1", author: "作者" });
    return books;
}

// config.exploreUrl 非空时必须提供。url 原样传入，不会替换 {{page}}，翻页请使用 page。
async function explore(url, page) {
    var html = await java.ajax(url);
    return [];
}

// 可选。返回字段会合并到搜索结果，tocUrl 为空时默认使用 bookUrl。
async function getBookInfo(book) {
    var html = await java.ajax(book.bookUrl);
    return {
        intro: "",
        coverUrl: "",
        latestChapterTitle: "",
        tocUrl: book.bookUrl
    };
}

// title 和 url 为必填字段，数组顺序即目录顺序。
async function getChapters(book) {
    var html = await java.ajax(book.tocUrl);
    var chapters = [];
    // chapters.push({ title: "第 1 章", url: "https://example.com/read/1" });
    return chapters;
}

// 返回正文文本，空字符串视为失败。
async function getContent(chapter, book, nextChapterUrl) {
    var html = await java.ajax(chapter.url);
    // 按站点修改选择器；java.getString 是兼容白名单中的文本提取接口。
    return java.getString("article@text", html);
}

/*
 * 段评统计与详情函数为可选功能。启用时请同时取消下面两个函数的注释。
 * getReviewSummary 返回 [{ paraIndex: 1, count: 5, paraData: "token" }]，paraIndex 为 -1 时表示章节标题；
 * getReviewDetail 返回 { items: [{ id: "comment-1", content: { text: "评论内容", replyCount: 3 } }], nextPageUrl: null }。
 * 可选的 getReviewReplies 返回 { items: [{ id: "reply-1", content: "回复内容" }] }，用于按评论 ID 和 page 加载更多回复。
 * id/reviewId 是不透明字符串；超过 JavaScript 安全整数范围的 ID 必须在 JSON.parse 前保留为字符串。
 * content 也可返回 { text, replyToName, img, audio, time, likeCount, replyCount }。
 * nextPageUrl 只是“是否还有下一页”的信号，翻页时应用会递增 page 参数再次调用。
 *
async function getReviewSummary(chapter, book) {
    var html = await java.ajax(config.bookSourceUrl + "/review/summary?url=" + chapter.url);
    return [];
}

async function getReviewDetail(chapter, book, paraIndex, paraData, page) {
    var html = await java.ajax(config.bookSourceUrl + "/review/detail?para=" + paraIndex + "&page=" + page);
    return { items: [], nextPageUrl: null };
}

// 可选。主评论需要提供非空 id 和 replyCount，且不要内嵌 replies；page=1 返回首批，空 items 表示结束。
async function getReviewReplies(chapter, book, paraIndex, paraData, reviewId, page) {
    var html = await java.ajax(config.bookSourceUrl + "/review/replies?id=" + reviewId + "&page=" + page);
    return { items: [] };
}
*/
