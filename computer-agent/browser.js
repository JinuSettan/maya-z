// Browser Companion session handler
const sessions = new Map();

async function openUrl(taskId, url) {
  sessions.set(taskId, { url, title: 'Web Document', text: `Page loaded: ${url}` });
  return {
    url,
    title: 'Web View',
    textContent: `Loaded page at ${url}`,
    requiresAuthentication: false
  };
}

async function inspectPage(taskId) {
  const session = sessions.get(taskId) || { url: 'about:blank', text: '' };
  return {
    url: session.url,
    title: 'Page View',
    textContent: session.text,
    requiresAuthentication: false
  };
}

module.exports = {
  openUrl,
  inspectPage
};
