'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright-core');

const ids = JSON.parse(fs.readFileSync('target/ui-ids.json', 'utf8'));
const env = Object.fromEntries(fs.readFileSync(path.join(process.env.LUMIS_AI_TOOLS, '.env'), 'utf8')
    .split(/\r?\n/).filter(line => /^\s*[A-Z_]+\s*=/.test(line))
    .map(line => { const pos = line.indexOf('='); return [line.slice(0, pos).trim(), line.slice(pos + 1).trim()]; }));
const base = (process.env.LUMIS_PORTAL_URL || env.LUMIS_PORTAL_URL).replace(/\/$/, '');
const endpoint = `${base}/br/com/lumis/commons/contentmigrator/hierarchicalcontentmigration`;
const failures = [];
const REQUIRED_FIELD_ENABLED = true;

async function login(browser, user, password) {
    console.log('PROGRESS authenticating browser session');
    assert.ok(user, 'Missing login user (check ai_tools/.env or scenario ids)');
    assert.equal(typeof password, 'string', 'Missing password setting');
    const context = await browser.newContext();
    context.setDefaultTimeout(20000);
    context.setDefaultNavigationTimeout(60000);
    const page = await context.newPage();
    await page.goto(`${base}/login.jsp`);
    await page.evaluate(async ({ user, password }) => {
        await fetch('login.jsp', {
            method: 'POST', credentials: 'same-origin',
            body: new URLSearchParams({ action: 'login', login: user, password })
        });
    }, { user, password });
    // A common user can authenticate and still receive 403 from the portal home.
    // A fresh context's server-issued login flag distinguishes that from rejection.
    const cookies = Object.fromEntries((await context.cookies(base)).map(cookie => [cookie.name, cookie.value]));
    assert.equal(cookies.lumIsLoggedUser, 'true', `Login rejected for ${user}`);
    return { context, page };
}

async function request(page, route, body) {
    // Chrome supplies Origin for POST and Referer for same-origin GET (the CSRF fallback).
    return page.evaluate(async ({ url, body }) => {
        const response = await fetch(url, {
            credentials: 'same-origin', method: body === undefined ? 'GET' : 'POST',
            headers: { Accept: 'application/json',
                ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
            ...(body === undefined ? {} : { body })
        });
        return { status: response.status, body: await response.text() };
    }, { url: endpoint + route, body });
}

async function setLocale(page, locale) {
    const status = await page.evaluate(async url => (await fetch(url, { method: 'POST', credentials: 'same-origin' })).status,
        `${base}/lumis/api/rest/lumsetlocale/${locale}`);
    assert.ok(status < 300, `Locale ${locale} rejected with HTTP ${status}`);
}

const checkbox = id => `#hcmList input[data-content-id="${id}"]`;
// DOUI's button renderer does not retain the control id on the clickable element.
const createButton = page => page.getByText(/^(Criar|Create)$/, { exact: true });

// Ids in the hidden field and ids of the enabled checkboxes checked on screen.
async function marks(page) {
    return page.evaluate(() => ({
        hidden: document.getElementById('selectedContentIds').value.split(',').filter(Boolean).sort(),
        checked: Array.from(document.querySelectorAll('#hcmList input[data-content-id]'))
            .filter(input => input.checked && !input.disabled).map(input => input.getAttribute('data-content-id')).sort()
    }));
}

async function openLevel(page, title, childTitle) {
    await page.locator('#hcmList li').filter({ hasText: title }).first().locator('button.hcm-enter').click();
    await page.locator('#hcmList li').filter({ hasText: childTitle }).waitFor();
}

async function openRoot(page) {
    await page.locator('#hcmCrumbs button').first().click();
    await page.locator(checkbox(ids.rootId)).waitFor();
}

// Waits until the form is rendered again (the old browser element is replaced).
async function waitForRerender(page, action) {
    await page.evaluate(() => document.getElementById('hcmBrowser').setAttribute('data-stale', 'true'));
    await action();
    await page.waitForFunction(() => {
        const browser = document.getElementById('hcmBrowser');
        return browser && !browser.hasAttribute('data-stale');
    }, null, { timeout: 30000 });
}

async function runCase(name, fn) {
    console.log(`PROGRESS ${name}`);
    try {
        await fn();
        console.log(`OK ${name}`);
    } catch (error) {
        failures.push(`${name}: ${error.message}`);
        console.error(`ERROR ${name}: ${error.message}`);
    }
}

async function main() {
    const browser = await chromium.launch({ channel: 'chrome', headless: true });
    try {
        const admin = await login(browser, env.LUMIS_ADMIN_USER, env.LUMIS_ADMIN_PASSWORD);
        const route = `/contents?serviceInstanceId=${encodeURIComponent(ids.originId)}`;
        await runCase('REST administrator GET 200', async () => {
            const result = await request(admin.page, route);
            assert.equal(result.status, 200);
            const data = JSON.parse(result.body);
            assert.ok(data.items.some(item => item.contentId === ids.rootId && !item.published));
        });
        await runCase('REST administrator POST selection 200', async () => {
            const result = await request(admin.page, '/selection', JSON.stringify({
                serviceInstanceId: ids.originId, contentIds: [ids.rootId]
            }));
            assert.equal(result.status, 200);
            const data = JSON.parse(result.body);
            assert.deepEqual(data.markedContentIds, [ids.rootId]);
            assert.equal(data.contentCount, 3);
        });
        const ordinary = await login(browser, ids.userLogin, ids.userPassword);
        await runCase('REST ordinary user GET 403', async () => {
            const result = await request(ordinary.page, route);
            assert.equal(result.status, 403);
        });
        await runCase('REST ordinary user malformed POST 403 without API payload', async () => {
            const result = await request(ordinary.page, '/selection', '{');
            // Authorization must run before the body is parsed: 403, never the 400 JSON error.
            // The container renders an entity-less 403 as its own HTML error page, so assert
            // that nothing from the API (a JSON object) is returned instead of an empty body.
            assert.equal(result.status, 403);
            assert.doesNotMatch(result.body, /^\s*\{/, 'Forbidden response must not carry an API JSON payload');
        });
        await ordinary.context.close();

        const popup = admin.page;
        popup.on('pageerror', error => console.error(`ERROR popup script: ${error.message}`));
        const popupUrl = `${base}/main.jsp?` + new URLSearchParams({
            lumPageId: 'LumisBlankPage', lumChannelId: ids.channelId,
            lumRTI: 'br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.addMigration',
            lumRTSI: ids.migrationInstanceId
        });
        for (const [locale, labels] of [
            ['en_US', { root: 'Contents', children: '1 child', childrenOf: '1 child of', trail: 'Breadcrumb' }],
            ['pt_BR', { root: 'Conteúdos', children: '1 filho', childrenOf: '1 filho de', trail: 'Trilha' }]]) {
            await runCase(`popup STR labels ${locale}`, async () => {
                await setLocale(popup, locale);
                await popup.goto(popupUrl);
                await popup.locator('#originServiceInstanceId').selectOption(ids.originId);
                const enter = popup.locator('#hcmList li').filter({ hasText: 'ui-root' }).locator('button.hcm-enter');
                await enter.waitFor();
                assert.equal((await popup.locator('#hcmCrumbs button').first().innerText()).trim(), labels.root);
                assert.equal(await popup.locator('#hcmCrumbs').evaluate(nav => nav.tagName + ':' + nav.getAttribute('aria-label')),
                    `NAV:${labels.trail}`);
                assert.match(await enter.innerText(), new RegExp(`${labels.children}$`));
                const title = await popup.locator('#hcmList li').filter({ hasText: 'ui-root' })
                    .locator('label span').first().textContent();
                assert.equal(await enter.getAttribute('aria-label'), `${labels.childrenOf} ${title}`);
                assert.doesNotMatch(await popup.locator('body').innerText(), /STR_/);
            });
        }
        await runCase('popup lists unpublished contents', async () => {
            await popup.goto(popupUrl);
            assert.ok(popup.url().includes('lumRTI='), 'Portal redirected the popup (check portal boot/health)');
            await popup.locator('#originServiceInstanceId').selectOption(ids.originId);
            await popup.locator('#hcmList li').filter({ hasText: 'ui-root' }).waitFor();
            assert.match(await popup.locator('#hcmList').innerText(), /Sem versão publicada|Not published/);
        });
        await runCase('popup level 2 and breadcrumb', async () => {
            await popup.locator('#hcmList li').filter({ hasText: 'ui-root' }).locator('button.hcm-enter').click();
            await popup.locator('#hcmList li').filter({ hasText: 'ui-child' }).waitFor();
            assert.match(await popup.locator('#hcmCrumbs [aria-current="page"]').innerText(), /ui-root/);
            await popup.locator('#hcmCrumbs button').first().click();
            await popup.locator('#hcmList li').filter({ hasText: 'ui-root' }).waitFor();
        });
        await runCase('popup level 3 breadcrumb back to root', async () => {
            await openLevel(popup, 'ui-root', 'ui-child');
            await openLevel(popup, 'ui-child', 'ui-grandchild');
            assert.equal(await popup.locator('#hcmCrumbs button').count(), 3);
            assert.equal(await popup.evaluate(() => document.activeElement.getAttribute('data-content-id')), ids.grandchildId);
            await openRoot(popup);
            assert.equal(await popup.locator('#hcmCrumbs button').count(), 1);
            assert.equal(await popup.evaluate(() => document.activeElement.getAttribute('data-content-id')), ids.rootId);
        });
        await runCase('popup rapid mark and unmark', async () => {
            const root = popup.locator(checkbox(ids.rootId));
            for (let index = 0; index < 5; index++) {
                await root.click({ noWaitAfter: true });
            }
            assert.deepEqual(await marks(popup), { hidden: [ids.rootId], checked: [ids.rootId] });
            await root.click();
            assert.deepEqual(await marks(popup), { hidden: [], checked: [] });
            // A late count response must not bring a mark back.
            await popup.waitForTimeout(1500);
            assert.deepEqual(await marks(popup), { hidden: [], checked: [] });
        });
        await runCase('popup child then ancestor', async () => {
            await openLevel(popup, 'ui-root', 'ui-child');
            await popup.locator(checkbox(ids.childId)).check();
            assert.deepEqual((await marks(popup)).hidden, [ids.childId]);
            await openRoot(popup);
            const root = popup.locator(checkbox(ids.rootId));
            assert.equal(await root.evaluate(input => input.indeterminate && !input.checked), true);
            await root.check();
            assert.deepEqual(await marks(popup), { hidden: [ids.rootId], checked: [ids.rootId] });
            assert.equal(await root.evaluate(input => input.indeterminate), false);
            await openLevel(popup, 'ui-root', 'ui-child');
            const child = popup.locator(checkbox(ids.childId));
            assert.deepEqual(await child.evaluate(input => [input.checked, input.disabled, input.getAttribute('aria-describedby')]),
                [true, true, 'hcmCoveredHint']);
            assert.ok((await popup.locator('#hcmCoveredHint').textContent()).length > 0);
            await child.click({ force: true });
            assert.equal(await child.evaluate(input => input.checked), true);
            assert.deepEqual((await marks(popup)).hidden, [ids.rootId]);
            await openRoot(popup);
        });
        await runCase('popup Space keeps focus', async () => {
            const root = popup.locator(checkbox(ids.rootId));
            await root.focus();
            await popup.keyboard.press('Space');
            assert.equal(await root.evaluate(input => document.activeElement === input && !input.checked), true);
            await popup.keyboard.press('Space');
            assert.equal(await root.evaluate(input => document.activeElement === input && input.checked), true);
            assert.deepEqual((await marks(popup)).hidden, [ids.rootId]);
        });
        await runCase('popup creation error keeps marks', async () => {
            await popup.locator('#name').fill('ui-smoke-same-instance');
            // Same origin and destination: the server refuses the creation and renders the form again.
            await popup.locator('#destServiceInstanceId').evaluate((select, value) => {
                select.appendChild(new Option('same', value));
                select.value = value;
            }, ids.originId);
            const onDialog = dialog => dialog.accept();
            popup.on('dialog', onDialog);
            try {
                await waitForRerender(popup, () => createButton(popup).click());
            } finally {
                popup.off('dialog', onDialog);
            }
            await popup.locator(checkbox(ids.rootId)).waitFor();
            await popup.waitForFunction(id => document.querySelector(`#hcmList input[data-content-id="${id}"]`).checked, ids.rootId);
            assert.deepEqual(await marks(popup), { hidden: [ids.rootId], checked: [ids.rootId] });
        });
        await runCase('popup origin change clears marks', async () => {
            await waitForRerender(popup, () => popup.locator('#originServiceInstanceId').selectOption(ids.destinationId));
            assert.deepEqual((await marks(popup)).hidden, []);
            await waitForRerender(popup, () => popup.locator('#originServiceInstanceId').selectOption(ids.originId));
            await popup.locator(checkbox(ids.rootId)).waitFor();
            assert.deepEqual(await marks(popup), { hidden: [], checked: [] });
        });
        if (!REQUIRED_FIELD_ENABLED) {
            console.log('SKIP popup required field message (REQUIRED_FIELD_ENABLED is false)');
        } else await runCase('popup required field message', async () => {
            await popup.goto(popupUrl);
            const requiredMessage = /\b(campo obrigat[oó]rio|required field)\b/i;
            const [message] = await Promise.all([
                Promise.any([
                    popup.waitForEvent('dialog', { timeout: 10000 }).then(async dialog => {
                        const text = dialog.message();
                        await dialog.accept();
                        return text;
                    }),
                    // DOUI validators may render a span instead of opening an alert.
                    // Match the singular error, excluding the static required-fields legend.
                    popup.waitForFunction(() => {
                        for (const element of document.querySelectorAll('span,[role="alert"],.lum-form__msg')) {
                            const text = `${element.innerText || ''} ${element.title || ''}`;
                            if (element.getClientRects().length && /\b(campo obrigat[oó]rio|required field)\b/i.test(text)) {
                                return text;
                            }
                        }
                        return false;
                    }, null, { timeout: 10000 }).then(async handle => {
                        try { return await handle.jsonValue(); }
                        finally { await handle.dispose(); }
                    })
                ]),
                createButton(popup).click()
            ]);
            assert.match(message, requiredMessage);
        });

        await runCase('edit selection restores paths, saves marks and refreshes the list', async () => {
            const editUrl = new URL(popupUrl);
            editUrl.searchParams.set('lumRTI', 'br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.editSelection');
            editUrl.searchParams.set('migrationId', ids.migrationId);
            await popup.goto(editUrl.toString());
            assert.equal(await popup.locator('#originServiceInstanceId').inputValue(), ids.originId, 'Edit form must preload its origin');
            assert.equal(await popup.locator('#migrationId').inputValue(), ids.migrationId, 'Edit form must identify the migration');
            await popup.locator(checkbox(ids.rootId)).waitFor();
            await popup.waitForFunction(id => document.querySelector(`#hcmList input[data-content-id="${id}"]`).indeterminate, ids.rootId);
            assert.deepEqual((await marks(popup)).hidden, [ids.childId]);
            await openLevel(popup, 'ui-root', 'ui-child');
            assert.equal(await popup.locator(checkbox(ids.childId)).isChecked(), true);
            await popup.locator(checkbox(ids.childId)).uncheck();
            await openRoot(popup);
            await popup.locator(checkbox(ids.rootId)).check();
            assert.deepEqual((await marks(popup)).hidden, [ids.rootId]);
            // Keep this direct runtime-interface page open after the successful process action.
            await popup.evaluate(() => { window.close = () => {}; });
            const [saved] = await Promise.all([
                popup.waitForResponse(response => response.request().method() === 'POST' && !response.url().startsWith(endpoint)),
                popup.getByText(/^(Salvar seleção|Save selection)$/, { exact: true }).click()
            ]);
            assert.equal(saved.status(), 200);
            await saved.finished();
            await popup.goto(editUrl.toString());
            await popup.waitForFunction(id => document.getElementById('selectedContentIds').value === id, ids.rootId);
            await popup.locator(checkbox(ids.rootId)).waitFor();
            assert.equal(await popup.locator(checkbox(ids.rootId)).isChecked(), true);
            const listUrl = new URL(popupUrl);
            listUrl.searchParams.set('lumRTI', 'br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.administration');
            await popup.goto(listUrl.toString());
            const row = popup.locator(`input[name="migrationList.tabulardata"][value="${ids.migrationId}"]`).locator('xpath=ancestor::li[contains(@class,"lum-list-item")]');
            assert.equal((await row.locator('.lum-field-id-markCount .lum-field-value').innerText()).trim(), '1');
            assert.ok(await popup.getByText(/^(Alterar seleção|Change selection)$/, { exact: true }).count());
        });
        await runCase('migration warning count and popup show real missing-parent warning', async () => {
            const listUrl = new URL(popupUrl);
            listUrl.searchParams.set('lumRTI', 'br.com.lumis.commons.contentmigrator.service.hierarchicalcontentmigration.administration');
            await popup.goto(listUrl.toString());
            const row = popup.locator(`input[name="migrationList.tabulardata"][value="${ids.migrationId}"]`).locator('xpath=ancestor::li[contains(@class,"lum-list-item")]');
            assert.equal((await row.locator('.lum-field-id-warningCount .lum-field-value').innerText()).trim(), '1');
            await row.locator('input[type="checkbox"]').check();
            await row.getByText(/^(Mais opções|More options)$/, { exact: true }).click();
            // The portal may reuse this runtime window for its named popup.
            const opened = Promise.any([
                popup.waitForEvent('popup'),
                popup.waitForNavigation().then(() => popup)
            ]);
            await row.locator('a[onclick*="migrationWarningsButton"]:visible').click();
            const warnings = await opened;
            await warnings.waitForLoadState();
            const body = await warnings.locator('body').innerText();
            assert.match(body, /ui-child/);
            assert.ok(body.includes(`Parent content ${ids.rootId}`));
            assert.match(body, /parent was left empty/);
            assert.doesNotMatch(body, /STR_/);
            if (warnings !== popup) await warnings.close();
        });
        if (failures.length) {
            await popup.screenshot({ path: 'target/ui-smoke-failure.png', fullPage: true });
            fs.writeFileSync('target/ui-smoke-failure.txt', failures.join('\n'));
            throw new Error(`${failures.length} smoke case(s) failed; see target/ui-smoke-failure.txt`);
        }
    } finally {
        await browser.close();
    }
}
main().catch(error => { console.error(`ERROR ${error.message}`); process.exitCode = 1; });
