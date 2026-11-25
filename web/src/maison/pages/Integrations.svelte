<script>
  import { onDestroy, onMount } from 'svelte';
  import { t } from '../../lib/i18n.svelte.js';
  import { home } from '../lib/home.svelte.js';
  import { gatewayApproval } from '../lib/gateway-approval.js';
  import { catalogue, installed, install, replace, remove, search, stateOf, guide } from '../lib/integrations.js';
  import Icon from '../ui/Icon.svelte';

  // The catalogue Moli carries, what this house set up here, and the state of
  // every driver (/api/health). One sheet at a time: a package to add, or one
  // of the house's to change.
  let packages = $state([]);
  let mine = $state([]);
  let drivers = $state([]);
  let query = $state('');
  let loading = $state(true);
  let loadError = $state(null);
  let sheet = $state(null);
  let form = $state(null);
  let pending = $state(false);
  let failure = $state(null);
  let awaitingPin = $state(false);
  let alive = true;
  let timer;

  const found = $derived(search(packages, query));
  const byId = $derived(Object.fromEntries(packages.map((p) => [p.id, p])));
  const mineNames = $derived(new Set(mine.map((m) => m.instance)));
  // The other drivers of the house that come from the catalogue (Moli's own
  // parts, such as people or outdoor, are not integrations).
  const others = $derived(drivers.filter((d) => !mineNames.has(d.instance) && byId[d.integration]));

  const approval = gatewayApproval({
    home,
    label: () => t('integrations.code'),
    waiting: (value) => { awaitingPin = value; },
    failed: (error) => { failure = { error }; },
    current: () => alive,
    perform: (intent) => run(intent, true),
  });

  function label(name) {
    const key = `integrations.champ.${name}`;
    const word = t(key);
    return word === key ? name : word;
  }

  async function refreshHealth() {
    try {
      const response = await fetch('/api/health');
      const data = await response.json();
      if (alive) drivers = Array.isArray(data?.drivers) ? data.drivers : [];
    } catch { /* the next round */ }
  }

  async function load(authenticated = false) {
    const [list, ours] = await Promise.all([catalogue(), installed()]);
    if (!alive) return;
    if (!list.ok || !ours.ok) {
      const error = !list.ok ? list.error : ours.error;
      if (error === 'auth' && !authenticated) { approval.ask({ kind: 'load' }); return; }
      loadError = error;
      loading = false;
      return;
    }
    packages = list.data;
    mine = ours.data;
    loadError = null;
    loading = false;
  }

  onMount(() => {
    load();
    refreshHealth();
    timer = setInterval(refreshHealth, 4000);
  });
  onDestroy(() => {
    alive = false;
    clearInterval(timer);
    approval.destroy();
  });

  function blank(pkg) {
    return {
      instance: '',
      options: Object.fromEntries(pkg.options.map((n) => [n, ''])),
      vars: Object.fromEntries(pkg.vars.map((n) => [n, ''])),
      secrets: Object.fromEntries(pkg.secrets.map((n) => [n, ''])),
      advanced: '',
      enabled: true,
    };
  }

  function openAdd(pkg) {
    if (!pkg.addable) return;
    sheet = { pkg, editing: null };
    form = blank(pkg);
    failure = null;
  }

  function openEdit(item) {
    const pkg = byId[item.integration];
    if (!pkg) return;
    const base = blank(pkg);
    sheet = { pkg, editing: item };
    form = {
      instance: item.instance,
      options: { ...base.options, ...item.options },
      vars: { ...base.vars, ...item.vars },
      secrets: base.secrets,
      advanced: item.advanced ?? '',
      enabled: item.enabled,
    };
    failure = null;
  }

  function close() {
    if (pending) return;
    sheet = null;
    form = null;
    failure = null;
  }

  async function run(intent, authenticated = false) {
    if (!alive) return;
    if (intent.kind === 'load') { await load(true); return; }
    pending = true;
    failure = null;
    const result = intent.kind === 'remove'
      ? await remove(intent.instance)
      : intent.instance
        ? await replace(intent.instance, intent.body)
        : await install(intent.body);
    if (!alive) return;
    pending = false;
    if (!result.ok) {
      if (result.error === 'auth' && !authenticated) { approval.ask(intent); return; }
      failure = result;
      return;
    }
    sheet = null;
    form = null;
    await load(true);
    refreshHealth();
  }

  function save(event) {
    event?.preventDefault();
    if (!sheet || pending) return;
    const body = {
      integration: sheet.pkg.id,
      options: form.options,
      vars: form.vars,
      secrets: Object.fromEntries(Object.entries(form.secrets).filter(([, v]) => v !== '')),
      advanced: form.advanced,
      enabled: form.enabled,
    };
    if (!sheet.editing && form.instance.trim()) body.instance = form.instance.trim();
    run({ kind: 'save', instance: sheet.editing?.instance, body });
  }

  function toggle(item) {
    if (pending) return;
    run({
      kind: 'save',
      instance: item.instance,
      body: { integration: item.integration, options: item.options, vars: item.vars, secrets: {}, advanced: item.advanced, enabled: !item.enabled },
    });
  }

  function drop(item) {
    if (pending) return;
    run({ kind: 'remove', instance: item.instance });
  }

  function elsewhere(pkg) {
    if (pkg.kind === 'airzone') return { text: t('integrations.ailleurs.airzone'), href: '#/airzone' };
    if (pkg.kind === 'phones') return { text: t('integrations.ailleurs.telephones') };
    return { text: t('integrations.ailleurs.fichier') };
  }
</script>

<section class="integrations">
  <header>
    <a class="back" href="#/systeme"><Icon name="chevron-left" size={18} />{t('integrations.retour')}</a>
    <h1>{t('integrations.titre')}</h1>
    <p class="sub">{t('integrations.sous_titre')}</p>
  </header>

  {#if loading}
    <p class="quiet">{t('integrations.chargement')}</p>
  {:else if loadError}
    <p class="problem">{t(`integrations.erreur.${loadError}`)}</p>
    <button class="primary" onclick={() => { loading = true; load(); }}>{t('integrations.reessayer')}</button>
  {:else}
    <h2>{t('integrations.maison.titre')}</h2>
    {#if mine.length === 0 && others.length === 0}
      <p class="quiet">{t('integrations.maison.vide')}</p>
    {/if}
    <div class="cards">
      {#each mine as item (item.instance)}
        {@const st = item.enabled ? stateOf(drivers, item.instance) : { state: 'off' }}
        <article class="card">
          <div class="top">
            <strong>{byId[item.integration]?.name ?? item.integration}</strong>
            <span class="state {st.state}">{t(`integrations.etat.${st.state}`)}</span>
          </div>
          <span class="instance">{item.instance}</span>
          {#if st.detail}<p class="detail">{st.detail}</p>{/if}
          <div class="actions">
            <button onclick={() => openEdit(item)} disabled={pending}><Icon name="pencil" size={16} />{t('integrations.modifier')}</button>
            <button onclick={() => toggle(item)} disabled={pending}>{item.enabled ? t('integrations.desactiver') : t('integrations.activer')}</button>
            <button class="danger" onclick={() => drop(item)} disabled={pending}>{t('integrations.retirer')}</button>
          </div>
        </article>
      {/each}
      {#each others as driver (driver.instance)}
        {@const st = stateOf(drivers, driver.instance)}
        <article class="card fixed">
          <div class="top">
            <strong>{byId[driver.integration]?.name ?? driver.integration}</strong>
            <span class="state {st.state}">{t(`integrations.etat.${st.state}`)}</span>
          </div>
          <span class="instance">{driver.instance}</span>
          {#if st.detail}<p class="detail">{st.detail}</p>{/if}
          <p class="note">{t('integrations.maison.ailleurs')}</p>
        </article>
      {/each}
    </div>

    <h2>{t('integrations.catalogue.titre')}</h2>
    <label class="search">
      <Icon name="search" size={18} />
      <input type="search" bind:value={query} placeholder={t('integrations.catalogue.chercher')} aria-label={t('integrations.catalogue.chercher')} />
    </label>
    <div class="grid">
      {#each found as pkg (pkg.id)}
        <button class="package" class:muted={!pkg.addable} onclick={() => openAdd(pkg)} disabled={!pkg.addable}>
          <strong>{pkg.name}</strong>
          {#if pkg.brands.length}<span class="brands">{pkg.brands.join(' · ')}</span>{/if}
          {#if pkg.addable}
            <span class="go"><Icon name="plus" size={16} />{t('integrations.catalogue.ajouter')}</span>
          {:else}
            <span class="note">{elsewhere(pkg).text}</span>
          {/if}
        </button>
      {/each}
    </div>
    {#if found.length === 0}<p class="quiet">{t('integrations.catalogue.rien')}</p>{/if}
  {/if}
</section>

{#if sheet}
  <div class="veil" role="presentation" onclick={close}></div>
  <div class="sheet" role="dialog" aria-modal="true" aria-label={sheet.pkg.name}>
    <header class="sheet-head">
      <div>
        <h2>{sheet.pkg.name}</h2>
        {#if sheet.pkg.brands.length}<span class="brands">{sheet.pkg.brands.join(' · ')}</span>{/if}
      </div>
      <button class="close" onclick={close} aria-label={t('integrations.fermer')}><Icon name="close" size={20} /></button>
    </header>
    <div class="sheet-body">
      <form onsubmit={save}>
        {#if !sheet.editing}
          <label class="field">
            <span>{t('integrations.champ_nom')}</span>
            <input bind:value={form.instance} placeholder={sheet.pkg.id} autocomplete="off" />
            <small>{t('integrations.champ_nom_aide')}</small>
          </label>
        {/if}
        {#each sheet.pkg.options as name (name)}
          <label class="field">
            <span>{label(name)}</span>
            <input bind:value={form.options[name]} autocomplete="off" />
          </label>
        {/each}
        {#each sheet.pkg.vars as name (name)}
          <label class="field">
            <span>{label(name)}</span>
            <input bind:value={form.vars[name]} autocomplete="off" />
          </label>
        {/each}
        {#each sheet.pkg.secrets as name (name)}
          <label class="field">
            <span>{label(name)} <em>{t('integrations.secret')}</em></span>
            <input type="password" bind:value={form.secrets[name]} autocomplete="new-password"
              placeholder={sheet.editing?.secrets.includes(name) ? t('integrations.secret_garde') : ''} />
          </label>
        {/each}
        <details open={!!form.advanced}>
          <summary>{t('integrations.avance')}</summary>
          <small>{t('integrations.avance_aide')}</small>
          <textarea bind:value={form.advanced} rows="6" spellcheck="false"></textarea>
        </details>
        {#if sheet.editing}
          <label class="switch">
            <input type="checkbox" bind:checked={form.enabled} />
            <span>{t('integrations.active')}</span>
          </label>
        {/if}
        {#if failure}
          <div class="problem">
            <p>{t(`integrations.erreur.${failure.error}`)}</p>
            {#if failure.detail}<pre>{failure.detail}</pre>{/if}
          </div>
        {/if}
        <div class="buttons">
          <button type="button" onclick={close} disabled={pending}>{t('integrations.annuler')}</button>
          <button type="submit" class="primary" disabled={pending || awaitingPin}>
            {pending ? t('integrations.en_cours') : sheet.editing ? t('integrations.enregistrer') : t('integrations.ajouter')}
          </button>
        </div>
      </form>
      <aside class="guide">
        <h3>{t('integrations.guide')}</h3>
        {#if sheet.pkg.onboarding}
          <!-- Escaped first, then a few tags (lib/integrations.js guide()). -->
          {@html guide(sheet.pkg.onboarding)}
        {:else}
          <p class="quiet">{t('integrations.guide_vide')}</p>
        {/if}
      </aside>
    </div>
  </div>
{/if}

<style>
  .integrations {
    max-width: 1180px;
  }

  .back {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    color: var(--ink-3);
    text-decoration: none;
    font-weight: 600;
    font-size: 14px;
  }

  h1 {
    margin: 6px 0 4px;
    font-size: 34px;
  }

  h2 {
    margin: 28px 0 12px;
    font-size: 18px;
  }

  .sub,
  .quiet,
  .note,
  .brands,
  .instance {
    color: var(--ink-3);
  }

  .sub {
    margin: 0;
  }

  .cards,
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
    gap: 12px;
  }

  .card,
  .package {
    display: grid;
    align-content: start;
    gap: 6px;
    padding: 16px;
    border-radius: 20px;
    background: var(--surface);
    box-shadow: var(--shadow);
    border: 0;
    text-align: left;
    color: var(--ink);
    font: inherit;
  }

  .card.fixed {
    background: color-mix(in srgb, var(--surface) 60%, transparent);
  }

  .package {
    cursor: pointer;
  }

  .package.muted {
    cursor: default;
    opacity: 0.6;
  }

  .top {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
  }

  .state {
    font-size: 12px;
    font-weight: 700;
    padding: 3px 10px;
    border-radius: 999px;
    background: var(--surface-2);
    white-space: nowrap;
  }

  .state.running {
    color: #1a7f37;
    background: color-mix(in srgb, #1a7f37 16%, transparent);
  }

  .state.waiting,
  .state.starting {
    color: #9a6700;
    background: color-mix(in srgb, #d4a017 18%, transparent);
  }

  .state.retrying {
    color: #c62828;
    background: color-mix(in srgb, #c62828 14%, transparent);
  }

  .detail {
    margin: 0;
    font-size: 13px;
    color: var(--ink-2);
  }

  .note,
  .brands,
  .instance {
    font-size: 13px;
  }

  .actions,
  .buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 6px;
  }

  button {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 9px 14px;
    border-radius: 999px;
    border: 0;
    background: var(--surface-2);
    color: var(--ink);
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }

  button:disabled {
    opacity: 0.5;
    cursor: default;
  }

  .primary {
    background: var(--sun);
    color: #161512;
  }

  .danger {
    color: #c62828;
  }

  .go {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    color: var(--ink-2);
    font-weight: 600;
    font-size: 13px;
  }

  .search {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 14px;
    margin-bottom: 14px;
    border-radius: 999px;
    background: var(--surface);
    box-shadow: var(--shadow);
    color: var(--ink-3);
  }

  .search input {
    flex: 1;
    border: 0;
    background: none;
    color: var(--ink);
    font: inherit;
    font-size: 16px;
    outline: none;
  }

  .veil {
    position: fixed;
    inset: 0;
    background: rgb(0 0 0 / 45%);
    z-index: 40;
  }

  .sheet {
    position: fixed;
    inset: 4vh 4vw;
    z-index: 41;
    display: flex;
    flex-direction: column;
    border-radius: 24px;
    background: var(--bg);
    box-shadow: var(--shadow-lift);
    overflow: hidden;
  }

  .sheet-head {
    display: flex;
    justify-content: space-between;
    align-items: start;
    gap: 12px;
    padding: 20px 24px 12px;
  }

  .sheet-head h2 {
    margin: 0;
  }

  .close {
    padding: 8px;
  }

  .sheet-body {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 24px;
    padding: 0 24px 24px;
    overflow-y: auto;
  }

  form {
    display: grid;
    align-content: start;
    gap: 14px;
  }

  .field {
    display: grid;
    gap: 6px;
  }

  .field span {
    font-weight: 600;
  }

  .field em {
    font-style: normal;
    font-weight: 500;
    color: var(--ink-3);
    font-size: 13px;
  }

  .field input,
  textarea {
    padding: 12px 14px;
    border-radius: 14px;
    border: 1px solid var(--line);
    background: var(--surface);
    color: var(--ink);
    font: inherit;
    font-size: 16px;
  }

  textarea {
    width: 100%;
    box-sizing: border-box;
    font-family: ui-monospace, monospace;
    font-size: 14px;
    margin-top: 8px;
  }

  small {
    color: var(--ink-3);
  }

  summary {
    font-weight: 600;
    cursor: pointer;
  }

  .switch {
    display: flex;
    align-items: center;
    gap: 10px;
    font-weight: 600;
  }

  .problem {
    padding: 12px 14px;
    border-radius: 14px;
    background: color-mix(in srgb, #c62828 12%, transparent);
    color: var(--ink);
  }

  .problem p {
    margin: 0;
  }

  .problem pre {
    margin: 8px 0 0;
    white-space: pre-wrap;
    font-size: 13px;
  }

  .guide {
    padding: 16px 20px;
    border-radius: 20px;
    background: var(--surface);
    color: var(--ink-2);
    font-size: 15px;
    line-height: 1.5;
    align-self: start;
  }

  .guide h3 {
    margin: 0 0 8px;
    color: var(--ink);
  }

  .guide :global(code) {
    font-size: 13px;
    background: var(--surface-2);
    padding: 1px 5px;
    border-radius: 6px;
  }

  .guide :global(pre) {
    overflow-x: auto;
    padding: 10px;
    border-radius: 10px;
    background: var(--surface-2);
  }

  .guide :global(h4) {
    margin: 14px 0 4px;
    color: var(--ink);
  }

  @media (max-width: 900px) {
    .sheet {
      inset: 2vh 2vw;
    }

    .sheet-body {
      grid-template-columns: minmax(0, 1fr);
    }
  }
</style>
