// GeckoView has no cookie manager: the app asks this extension for a URL's cookies. Partitioned
// ones are returned only when asked for by a partition key, and Cloudflare partitions its clearance.
const port = browser.runtime.connectNative("probe");
port.onMessage.addListener(async (message) => {
  const found = new Map();
  for (const query of [{ url: message.url }, { url: message.url, partitionKey: {} }]) {
    try {
      for (const c of await browser.cookies.getAll(query)) found.set(c.name, c.value);
    } catch (e) {}
  }
  port.postMessage({ agent: navigator.userAgent, cookies: [...found].map(([name, value]) => ({ name, value })) });
});
