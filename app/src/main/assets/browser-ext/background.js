// GeckoView has no cookie manager: the app asks this extension, which runs inside the engine,
// for the cookies a session holds, and hands it the ones a session starts with. Every message
// carries the id of the question it answers. It also tells the app, unasked, the status a page
// came back with, which GeckoView does not say: a file host that lets the browser through answers
// 416 to the range the app asks of a guarded file.
const PRIVATE = "firefox-private";
const port = browser.runtime.connectNative("booruvault");

// Partitioned cookies are returned only when asked for by a partition key, and Cloudflare
// partitions its clearance.
async function cookiesOf(url) {
  const found = new Map();
  for (const query of [{ url, storeId: PRIVATE }, { url, storeId: PRIVATE, partitionKey: {} }]) {
    try {
      for (const c of await browser.cookies.getAll(query)) {
        found.set(c.name + "|" + c.domain + "|" + c.path, { name: c.name, value: c.value, httpOnly: c.httpOnly });
      }
    } catch (e) {}
  }
  return [...found.values()];
}

// Through Tor, the app has every request go to Orbot's port under the credential its own client
// uses: Tor keeps streams with different credentials on different circuits, and the engine's
// preferences can name a SOCKS proxy but no credential for it. Told nothing, or after a restart
// of this script, a request is left to the preferences, which are what decides the route: this
// never names a direct way.
let socks = null;
browser.proxy.onRequest.addListener(
  () => {
    if (!socks) return undefined;
    return { type: "socks", host: "127.0.0.1", port: socks.port, username: socks.username, password: socks.password, proxyDNS: true };
  },
  { urls: ["<all_urls>"] },
);

// With the status goes whether the page is an anti-bot check's own, which Cloudflare says in a
// header of the answer: a session may hold a clearance cookie while the check is still on.
browser.webRequest.onHeadersReceived.addListener(
  (details) => {
    const challenge = (details.responseHeaders || []).some(
      (h) => h.name.toLowerCase() === "cf-mitigated" && (h.value || "").toLowerCase() === "challenge",
    );
    port.postMessage({ event: "status", url: details.url, status: details.statusCode, challenge });
  },
  { urls: ["<all_urls>"], types: ["main_frame"] },
  ["responseHeaders"],
);

port.onMessage.addListener(async (message) => {
  const answer = { id: message.id };
  try {
    if (message.op === "cookies") {
      answer.cookies = {};
      for (const url of message.urls) answer.cookies[url] = await cookiesOf(url);
    } else if (message.op === "setCookies") {
      for (const c of message.cookies) {
        // A cookie with a domain is the site's, as a clearance its other hosts check too; one
        // without stays on the host of its address. HttpOnly as the app asks: no script of the
        // page reads or overwrites it.
        const cookie = { url: c.url, name: c.name, value: c.value, path: "/", secure: c.secure, httpOnly: !!c.httpOnly, storeId: PRIVATE };
        if (c.domain) cookie.domain = c.domain;
        await browser.cookies.set(cookie);
      }
    } else if (message.op === "clearPrivate") {
      // The engine's own wipe leaves the private store, which goes only with the last private
      // session; partitioned cookies are listed only when asked for by a partition key.
      for (const query of [{ storeId: PRIVATE }, { storeId: PRIVATE, partitionKey: {} }]) {
        let found = [];
        try {
          found = await browser.cookies.getAll(query);
        } catch (e) {
          continue;
        }
        for (const c of found) {
          const removal = { url: (c.secure ? "https://" : "http://") + c.domain.replace(/^\./, "") + c.path, name: c.name, storeId: PRIVATE };
          if (c.partitionKey) removal.partitionKey = c.partitionKey;
          await browser.cookies.remove(removal);
        }
      }
    } else if (message.op === "route") {
      socks = message.socks || null;
    } else {
      answer.error = "unknown op";
    }
  } catch (e) {
    answer.error = String(e);
  }
  port.postMessage(answer);
});
