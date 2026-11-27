// Runs in each page. GeckoView has no call that returns what a page holds, so where the app
// listens on a session, as it does on the hidden browser's, this script hands it the page's HTML
// when asked, and says whether the page has finished loading. Where the app does not listen, the
// line never opens and nothing is read.
// The app's name on this line is a native application's: letters, digits and underscores.
// A page larger than the app's client reads of any page is not handed over at all.
const MAX_CHARS = 8 * 1024 * 1024;
let port = null;
try {
  port = browser.runtime.connectNative("booruvault_page");
} catch (e) {}
if (port) {
  port.onMessage.addListener((message) => {
    if (message.op !== "html") return;
    const html = document.documentElement.outerHTML;
    const answer = { id: message.id, url: location.href, state: document.readyState };
    if (html.length > MAX_CHARS) answer.tooLarge = true;
    else answer.html = html;
    port.postMessage(answer);
  });
}
