// Integrations set up from the dashboard: the catalogue Moli carries, the
// ones this house added, adding, changing and removing one. A secret typed
// here goes to Moli's vault and never comes back. Authentication is left to
// the caller (a 403 is `auth`), so a refused write is never replayed silently.

const ERRORS = new Set([
  'unknown_integration', 'not_addable', 'invalid_instance', 'invalid_field', 'invalid_advanced',
  'invalid_options', 'duplicate_instance', 'configuration_locked', 'not_found', 'limit_reached',
  'persistence_failed', 'no_vault', 'unavailable', 'shutting_down', 'busy',
]);

async function call(path, { method = 'GET', body, fetcher = globalThis.fetch } = {}) {
  let response;
  try {
    response = await fetcher(path, {
      method,
      headers: { 'x-moli-origin': 'ui', ...(body ? { 'content-type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
  } catch {
    return { ok: false, error: 'network' };
  }
  if (response.status === 403) return { ok: false, error: 'auth', status: 403 };
  if (response.status === 204) return { ok: true, data: null };
  let data = null;
  try { data = await response.json(); } catch { /* not JSON */ }
  if (!response.ok) {
    const error = ERRORS.has(data?.error) ? data.error : response.status === 429 ? 'busy' : 'server';
    return { ok: false, error, status: response.status, detail: typeof data?.detail === 'string' ? data.detail.slice(0, 600) : undefined };
  }
  return { ok: true, data };
}

export const catalogue = (fetcher) => call('/api/integrations/catalogue', { fetcher });
export const installed = (fetcher) => call('/api/integrations/installed', { fetcher });
export const install = (body, fetcher) => call('/api/integrations/installed', { method: 'POST', body, fetcher });
export const replace = (instance, body, fetcher) =>
  call(`/api/integrations/installed/${encodeURIComponent(instance)}`, { method: 'PUT', body, fetcher });
export const remove = (instance, fetcher) =>
  call(`/api/integrations/installed/${encodeURIComponent(instance)}`, { method: 'DELETE', fetcher });

/** Packages matching a search (name, id, brands, models), addable first, then by name. */
export function search(packages, query) {
  const words = String(query ?? '').toLowerCase().normalize('NFD').replace(/\p{M}/gu, '').split(/\s+/).filter(Boolean);
  const plain = (s) => String(s).toLowerCase().normalize('NFD').replace(/\p{M}/gu, '');
  return packages
    .filter((p) => {
      const text = plain([p.name, p.id, ...(p.brands ?? []), ...(p.models ?? [])].join(' '));
      return words.every((w) => text.includes(w));
    })
    .sort((a, b) => Number(b.addable) - Number(a.addable) || a.name.localeCompare(b.name, 'fr'));
}

/** The state of a driver as /api/health tells it: running, starting, waiting, retrying, stopped. */
export function stateOf(drivers, instance) {
  const status = (drivers ?? []).find((d) => d.instance === instance)?.status;
  if (!status) return { state: 'absent' };
  if (status.state === 'backoff') return { state: 'retrying', detail: status.error };
  if (status.state === 'waiting') return { state: 'waiting', detail: status.reason };
  return { state: status.state };
}

const escape = (s) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

function inline(text) {
  return escape(text)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1');
}

/**
 * An onboarding guide (Markdown) as safe HTML: headings, paragraphs, lists,
 * code. Every character is escaped first; links keep their text only.
 */
export function guide(markdown) {
  const out = [];
  let list = null;
  let code = null;
  const close = () => { if (list) { out.push(`</${list}>`); list = null; } };
  for (const raw of String(markdown ?? '').split('\n')) {
    if (raw.trim().startsWith('```')) {
      if (code === null) { close(); code = []; } else { out.push(`<pre><code>${escape(code.join('\n'))}</code></pre>`); code = null; }
      continue;
    }
    if (code !== null) { code.push(raw); continue; }
    const line = raw.trim();
    const heading = line.match(/^(#{1,4})\s+(.*)$/);
    const bullet = line.match(/^[-*]\s+(.*)$/);
    const numbered = line.match(/^\d+\.\s+(.*)$/);
    if (heading) { close(); out.push(`<h4>${inline(heading[2])}</h4>`); }
    else if (bullet || numbered) {
      const kind = bullet ? 'ul' : 'ol';
      if (list !== kind) { close(); out.push(`<${kind}>`); list = kind; }
      out.push(`<li>${inline((bullet ?? numbered)[1])}</li>`);
    } else if (!line) close();
    else if (list && raw.startsWith('  ')) out[out.length - 1] = out[out.length - 1].replace(/<\/li>$/, ` ${inline(line)}</li>`);
    else { close(); out.push(`<p>${inline(line)}</p>`); }
  }
  close();
  if (code !== null) out.push(`<pre><code>${escape(code.join('\n'))}</code></pre>`);
  return out.join('');
}
