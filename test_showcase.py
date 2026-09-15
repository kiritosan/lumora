import functools
import http.server
import json
import threading
import unittest
from pathlib import Path

from playwright.sync_api import sync_playwright


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass


class ShowcaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        handler = functools.partial(QuietHandler, directory=str(Path(__file__).parent))
        cls.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f'http://127.0.0.1:{cls.server.server_port}'
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch()

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self.context = self.browser.new_context(viewport={'width': 1440, 'height': 1000})
        self.page = self.context.new_page()
        self.errors = []
        self.page.on('pageerror', lambda error: self.errors.append(str(error)))
        self.page.goto(self.url)
        self.page.wait_for_selector('#stage[aria-busy="false"]')
        self.active = self.page.locator('#preview > .collectible:not(.ghost)')

    def tearDown(self):
        self.context.close()
        self.assertEqual(self.errors, [])

    def shape(self, name):
        self.page.locator(f'button[data-shape="{name}"]').click()

    def geometry(self):
        return self.page.locator('#preview').evaluate('''el => ({
            width: el.offsetWidth, height: el.offsetHeight,
            cards: [...el.children].map(card => {
                const r = card.getBoundingClientRect();
                return { shape: card.dataset.shape, width: card.offsetWidth,
                    height: card.offsetHeight, cy: r.y + r.height / 2, cx: r.x + r.width / 2,
                    art: card.querySelector('.art-window').clientHeight };
            })
        })''')

    def test_shapes_have_stable_track_and_round_badges(self):
        ratios = {'card': .71, 'badge': 1, 'square': 1, 'arch': .71, 'polaroid': .82}
        for width in [1440, 768, 390]:
            self.page.set_viewport_size({'width': width, 'height': 1000})
            for shape, ratio in ratios.items():
                with self.subTest(width=width, shape=shape):
                    self.shape(shape)
                    self.page.mouse.move(0, 0)
                    self.page.wait_for_timeout(750)
                    geometry = self.geometry()
                    self.assertAlmostEqual(geometry['width'] / geometry['height'], .71, delta=.01)
                    self.assertAlmostEqual(geometry['cards'][0]['width'] / geometry['cards'][0]['height'], ratio, delta=.01)
                    centers = [card['cy'] for card in geometry['cards']]
                    self.assertLess(max(centers) - min(centers), 2)
                    for card in geometry['cards'][1:]:
                        self.assertAlmostEqual(abs(card['cx'] - geometry['cards'][0]['cx']), geometry['width'] * 1.12, delta=2)
                    for card in geometry['cards']:
                        self.assertGreater(card['art'], 0)
                        if card['shape'] == 'badge':
                            self.assertAlmostEqual(card['width'], card['height'], delta=1)
                    self.assertFalse(self.page.evaluate('document.documentElement.scrollWidth > innerWidth'))

    def test_flip_and_carousel_reset(self):
        for shape in ['card', 'badge', 'square', 'arch', 'polaroid']:
            self.shape(shape)
            self.page.locator('#flipBtn').click()
            self.page.wait_for_timeout(750)
            self.assertEqual(self.active.locator('.card-back').get_attribute('aria-hidden'), 'false')
            self.assertLess(self.active.evaluate('el => new DOMMatrixReadOnly(getComputedStyle(el).transform).m33'), -.9)
            self.page.locator('#flipBtn').click()
            self.page.wait_for_timeout(750)
            self.assertGreater(self.active.evaluate('el => new DOMMatrixReadOnly(getComputedStyle(el).transform).m33'), .9)
        self.page.locator('#flipBtn').click()
        self.page.locator('#nextBtn').click()
        self.page.wait_for_timeout(900)
        self.assertEqual(self.page.locator('#flipBtn').get_attribute('aria-pressed'), 'false')
        self.assertEqual(self.active.locator('.card-back').get_attribute('aria-hidden'), 'true')
        geometry = self.geometry()
        centers = [card['cy'] for card in geometry['cards']]
        self.assertLess(max(centers) - min(centers), 2)

    def test_material_export_and_persistence(self):
        for index, material in enumerate(['holo', 'pearl', 'metal', 'matte', 'glass', 'brushed', 'silk', 'prismatic']):
            shape = ['card', 'badge', 'square', 'arch', 'polaroid'][index % 5]
            self.shape(shape)
            self.page.locator(f'button[data-material="{material}"]').click()
            self.assertEqual(self.active.get_attribute('data-material'), material)
            self.page.locator('#exportBtn').click()
            self.page.wait_for_selector('#exportDialog[open]')
            image = self.page.locator('#exportPreview')
            self.page.wait_for_function('document.getElementById("exportPreview").naturalWidth > 0')
            dimensions = image.evaluate('el => [el.naturalWidth, el.naturalHeight]')
            ratio = {'card': .71, 'badge': 1, 'square': 1, 'arch': .71, 'polaroid': .82}[shape]
            self.assertEqual(dimensions[0], 1056)
            self.assertAlmostEqual(dimensions[1], round(816 / ratio) + 240, delta=1)
            self.page.locator('#closeExport').click()
        self.page.locator('#saveBtn').click()
        self.page.wait_for_timeout(800)
        self.page.reload()
        self.page.wait_for_selector('#stage[aria-busy="false"]')
        self.assertEqual(self.active.get_attribute('data-material'), 'prismatic')
        self.assertEqual(self.active.get_attribute('data-shape'), 'square')
        self.page.locator('button[data-filter="square"]').click()
        self.assertEqual(self.page.locator('.collection-item').count(), 1)

    def test_swipe_crosses_shapes_without_vertical_jump(self):
        self.page.locator('#stage').scroll_into_view_if_needed()
        stage = self.page.locator('#stage').bounding_box()
        x, y = stage['x'] + stage['width'] / 2, stage['y'] + stage['height'] / 2
        self.page.mouse.move(x, y)
        self.page.mouse.down()
        self.page.mouse.move(x - 25, y, steps=3)
        self.page.wait_for_timeout(50)
        center = self.geometry()['cards'][0]['cy']
        for distance in [90, 160, 240]:
            self.page.mouse.move(x - distance, y, steps=8)
            self.page.wait_for_timeout(40)
            for card in self.geometry()['cards']:
                self.assertAlmostEqual(card['cy'], center, delta=2)
        self.page.mouse.up()
        self.page.mouse.move(0, 0)
        self.page.wait_for_timeout(900)
        self.assertEqual(self.page.locator('#preview.dragging').count(), 0)

    def test_physics_dialog_keyboard_bounds_and_mobile(self):
        for width in [1440, 390]:
            self.page.set_viewport_size({'width': width, 'height': 900})
            self.page.locator('#physicsBtn').click()
            dialog = self.page.locator('.physics-dialog')
            self.assertTrue(dialog.is_visible())
            self.assertEqual(dialog.locator('.physics-body').count(), 4)
            self.page.wait_for_timeout(2300)
            self.assertTrue(dialog.evaluate('el => el.scrollWidth <= el.clientWidth'))
            bounds = self.page.locator('.physics-arena').evaluate('''el => {
                const arena = el.getBoundingClientRect();
                return [...el.querySelectorAll('.physics-body')].map(b => {
                    const r = b.getBoundingClientRect();
                    return r.left >= arena.left && r.right <= arena.right && r.top >= arena.top && r.bottom <= arena.bottom;
                });
            }''')
            self.assertTrue(all(bounds))
            dialog.get_by_role('button', name='暂停物理模拟', exact=True).click()
            body = dialog.locator('.physics-body').first
            body.focus()
            before = body.bounding_box()
            body.press('ArrowUp')
            self.assertLess(body.bounding_box()['y'], before['y'])
            body.press('Space')
            self.assertEqual(dialog.get_by_role('button', name='暂停物理模拟', exact=True).count(), 1)
            body.press('Escape')
            self.page.wait_for_selector('.physics-dialog', state='detached')
            self.assertEqual(self.page.locator('.physics-dialog').count(), 0)
            self.assertEqual(self.page.evaluate('document.activeElement.id'), 'physicsBtn')

    def test_reduced_motion_flip_and_static_physics(self):
        self.page.emulate_media(reduced_motion='reduce')
        self.page.locator('#flipBtn').click()
        self.assertLess(self.active.evaluate('el => new DOMMatrixReadOnly(getComputedStyle(el).transform).m33'), -.9)
        self.page.locator('#physicsBtn').click()
        dialog = self.page.locator('.physics-dialog')
        self.assertEqual(dialog.get_by_role('button', name='继续物理模拟', exact=True).count(), 1)
        self.assertTrue(dialog.locator('#impactGlow').is_disabled())
        self.assertFalse(dialog.locator('#impactGlow').is_checked())
        self.assertTrue(dialog.locator('#physicsShake').is_disabled())
        body = dialog.locator('.physics-body').first
        before = body.bounding_box()
        self.page.wait_for_timeout(400)
        self.assertEqual(before, body.bounding_box())
        self.page.keyboard.press('Escape')

    def test_physics_throw_cancel_and_sensor_denial(self):
        self.page.locator('#physicsBtn').click()
        dialog = self.page.locator('.physics-dialog')
        dialog.get_by_role('checkbox', name='重力', exact=True).uncheck()
        dialog.get_by_role('button', name='重新摆放', exact=True).click()
        body = dialog.locator('.physics-body').first
        bounds = body.bounding_box()
        x, y = bounds['x'] + bounds['width'] / 2, bounds['y'] + bounds['height'] / 2
        self.page.mouse.move(x, y)
        self.page.mouse.down(button='right')
        self.assertIsNone(body.get_attribute('data-dragging'))
        self.page.mouse.up(button='right')
        self.page.mouse.down()
        self.assertEqual(body.get_attribute('data-dragging'), 'true')
        for offset in [15, 35, 60, 90]:
            self.page.mouse.move(x + offset, y - 15, steps=4)
            self.page.wait_for_timeout(20)
        self.page.mouse.up()
        self.assertIsNone(body.get_attribute('data-dragging'))
        self.page.wait_for_timeout(100)
        self.assertGreater(body.bounding_box()['x'], bounds['x'] + 10)
        dialog.get_by_role('button', name='暂停物理模拟', exact=True).click()
        body.hover()
        self.page.mouse.down()
        self.assertEqual(body.get_attribute('data-dragging'), 'true')
        body.evaluate('''el => el.dispatchEvent(new PointerEvent('pointercancel', { bubbles: true, pointerId: 1 }))''')
        self.page.mouse.up()
        self.assertIsNone(body.get_attribute('data-dragging'))
        self.page.evaluate('window.DeviceOrientationEvent = { requestPermission: async () => "denied" }')
        dialog.get_by_role('button', name='启用倾斜重力', exact=True).click()
        self.page.wait_for_function('document.getElementById("physicsSensorStatus").textContent.includes("未获得")')
        self.assertEqual(dialog.get_by_role('button', name='启用倾斜重力', exact=True).get_attribute('aria-pressed'), 'false')

    def test_magnet_shake_controls_and_pause(self):
        self.page.locator('#physicsBtn').click()
        dialog = self.page.locator('.physics-dialog')
        mode = dialog.locator('#physicsMagnetMode')
        mode.wait_for(timeout=1500)
        self.assertEqual(mode.input_value(), 'off')
        self.assertEqual(dialog.locator('#impactSoundMode').input_value(), 'off')
        self.assertTrue(dialog.locator('#physicsMagnetPulse').is_disabled())
        dialog.get_by_role('checkbox', name='重力', exact=True).uncheck()
        arena = dialog.locator('.physics-arena')
        body = dialog.locator('.physics-body').first
        for magnet_mode in ['attract', 'repel']:
            mode.select_option(magnet_mode)
            dialog.get_by_role('button', name='重新摆放', exact=True).click()
            arena.scroll_into_view_if_needed()
            area = arena.bounding_box()
            x, y = area['x'] + area['width'] / 2, area['y'] + area['height'] / 2
            before = body.bounding_box()
            distance = lambda box: ((box['x'] + box['width'] / 2 - x) ** 2 + (box['y'] + box['height'] / 2 - y) ** 2) ** .5
            self.page.mouse.move(x, y)
            self.page.mouse.down()
            self.page.wait_for_timeout(300)
            after = body.bounding_box()
            self.assertTrue(dialog.locator('.physics-field').is_visible())
            self.page.mouse.up()
            self.assertFalse(dialog.locator('.physics-field').is_visible())
            if magnet_mode == 'attract':
                self.assertLess(distance(after), distance(before) - 5)
            else:
                self.assertGreater(distance(after), distance(before) + 5)
        dialog.get_by_role('button', name='重新摆放', exact=True).click()
        before = body.bounding_box()
        dialog.locator('#physicsShake').click()
        self.page.wait_for_timeout(180)
        self.assertNotEqual(body.bounding_box(), before)
        dialog.locator('#physicsMagnetPulse').click()
        self.assertTrue(dialog.locator('.physics-field').is_visible())
        dialog.get_by_role('button', name='暂停物理模拟', exact=True).click()
        self.assertFalse(dialog.locator('.physics-field').is_visible())
        self.assertTrue(dialog.locator('#physicsMagnetPulse').is_disabled())
        self.assertTrue(dialog.locator('#physicsShake').is_disabled())
        self.assertEqual(dialog.locator('.physics-impact-glow').count(), 0)

    def test_collision_audio_glow_and_cleanup(self):
        self.page.evaluate('''() => {
            const NativeAudio = window.AudioContext;
            window.audioProbe = { contexts: [], masters: [], starts: 0, glows: 0, maxGlows: 0 };
            window.AudioContext = class extends NativeAudio {
                constructor(...args) { super(...args); audioProbe.contexts.push(this); this.firstGain = true; }
                createGain() { const gain = super.createGain(); if (this.firstGain) { audioProbe.masters.push(gain); this.firstGain = false; } return gain; }
                createOscillator() { const source = super.createOscillator(); const start = source.start.bind(source); source.start = (...args) => { audioProbe.starts++; return start(...args); }; return source; }
            };
            new MutationObserver(records => {
                for (const record of records) for (const el of record.addedNodes) if (el.nodeType === 1 && el.matches('.physics-impact-glow')) audioProbe.glows++;
                audioProbe.maxGlows = Math.max(audioProbe.maxGlows, document.querySelectorAll('.physics-impact-glow').length);
            }).observe(document.body, { childList: true, subtree: true });
        }''')
        self.page.locator('#physicsBtn').click()
        dialog = self.page.locator('.physics-dialog')
        self.assertEqual(self.page.evaluate('audioProbe.contexts.length'), 0)
        sound = dialog.locator('#impactSoundMode')
        sound.select_option('material')
        self.page.wait_for_function('document.getElementById("impactFeedbackStatus").textContent.includes("材质音已开启")')
        self.assertEqual(self.page.evaluate('audioProbe.contexts.length'), 1)
        self.page.evaluate('''() => {
            audioProbe.analyser = audioProbe.contexts[0].createAnalyser();
            audioProbe.masters[0].connect(audioProbe.analyser);
        }''')
        measure = '''() => new Promise(resolve => {
            const start = performance.now(), data = new Float32Array(2048);
            let peak = 0;
            function frame() {
                audioProbe.analyser.getFloatTimeDomainData(data);
                for (const value of data) peak = Math.max(peak, Math.abs(value));
                if (performance.now() - start > 1500) resolve(peak); else requestAnimationFrame(frame);
            }
            frame();
        })'''
        for mode in ['material', 'music']:
            sound.select_option(mode)
            self.page.wait_for_function('document.getElementById("impactSoundMode").value !== "off" && !document.getElementById("impactFeedbackStatus").textContent.includes("正在启用")')
            dialog.get_by_role('button', name='重新摆放', exact=True).click()
            peak = self.page.evaluate(measure)
            self.assertGreater(peak, .00001)
            self.assertLess(peak, .2)
        self.assertGreater(self.page.evaluate('audioProbe.glows'), 0)
        self.assertLessEqual(self.page.evaluate('audioProbe.maxGlows'), 6)
        self.page.evaluate('''() => {
            const volume = document.getElementById('impactVolume');
            volume.value = '0'; volume.dispatchEvent(new Event('input', { bubbles: true }));
        }''')
        starts = self.page.evaluate('audioProbe.starts')
        dialog.get_by_role('button', name='重新摆放', exact=True).click()
        self.page.wait_for_timeout(1300)
        self.assertEqual(self.page.evaluate('audioProbe.starts'), starts)
        dialog.locator('#impactGlow').uncheck()
        self.assertEqual(dialog.locator('.physics-impact-glow').count(), 0)
        sound.select_option('off')
        dialog.get_by_role('button', name='关闭物理把玩', exact=True).click()
        self.page.wait_for_selector('.physics-dialog', state='detached')
        self.page.wait_for_function('audioProbe.contexts.every(context => context.state === "closed")')
        self.page.locator('#physicsBtn').click()
        self.assertEqual(self.page.locator('#impactSoundMode').input_value(), 'off')
        self.assertEqual(self.page.evaluate('audioProbe.contexts.length'), 1)
        self.page.evaluate('window.AudioContext = undefined; window.webkitAudioContext = undefined')
        self.page.locator('#impactSoundMode').select_option('material')
        self.page.wait_for_function('document.getElementById("impactFeedbackStatus").textContent.includes("不支持")')
        self.assertEqual(self.page.locator('#impactSoundMode').input_value(), 'off')

    def test_cloud_sync_push_pull_and_backup(self):
        pulled = {'rows': []}
        pushed = []
        def handle(route):
            request = route.request
            if request.method == 'GET':
                route.fulfill(status=200, content_type='application/json', body=json.dumps(pulled['rows']))
            else:
                pushed.extend(json.loads(request.post_data))
                route.fulfill(status=201, content_type='application/json', body='[]')
        self.page.route('**/rest/v1/shiguang_items**', handle)
        self.page.locator('#cloudBtn').click()
        dialog = self.page.locator('#syncDialog')
        self.assertTrue(dialog.is_visible())
        self.assertTrue(dialog.locator('#syncNowBtn').is_disabled())
        self.page.locator('#syncConnect').click()
        self.page.wait_for_selector('.toast.visible')
        dialog.locator('#syncUrl').fill('https://demo.supabase.co')
        dialog.locator('#syncAnon').fill('a' * 30)
        dialog.locator('#syncGenerate').click()
        self.assertGreaterEqual(len(dialog.locator('#syncKey').input_value()), 8)
        self.page.locator('#syncConnect').click()
        self.page.wait_for_function('document.getElementById("syncStatus").textContent.includes("同步完成")')
        self.assertFalse(dialog.locator('#syncNowBtn').is_disabled())
        dialog.locator('#closeSync').click()
        title = self.page.locator('#titleInput')
        title.fill('云端测试藏品')
        title.dispatch_event('change')
        for _ in range(80):
            if pushed:
                break
            self.page.wait_for_timeout(100)
        self.assertTrue(pushed)
        self.assertFalse(any(row['deleted'] for row in pushed))
        self.assertTrue(all(row['id'] != '__meta__' for row in pushed))
        pulled['rows'] = [{'id': 'remote-1', 'updated': 4102444800000, 'deleted': False,
            'data': {'v': 1, 'enc': 'none', 'json': json.dumps({'id': 'remote-1', 'title': '远处来的藏品', 'image': 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'created': 4102444800000, 'updated': 4102444800000})}}]
        self.page.locator('#cloudBtn').click()
        dialog.locator('#syncNowBtn').click()
        self.page.wait_for_function('document.getElementById("syncStatus").textContent.includes("更新")')
        dialog.locator('#closeSync').click()
        self.assertTrue(self.page.locator('.collection-item', has_text='远处来的藏品').count() > 0)
        self.page.locator('#cloudBtn').click()
        with self.page.expect_download() as download:
            dialog.locator('#backupExport').click()
        path = download.value.path()
        payload = json.loads(Path(path).read_text(encoding='utf-8'))
        self.assertEqual(payload['app'], 'shiguang')
        self.assertTrue(any(item['title'] == '远处来的藏品' for item in payload['items']))
        dialog.locator('#syncDisconnect').click()
        self.assertTrue(dialog.locator('#syncNowBtn').is_disabled())

    def test_offline_assets_include_museum_and_physics(self):
        self.page.evaluate('navigator.serviceWorker.ready.then(() => true)')
        self.page.wait_for_function('navigator.serviceWorker.controller !== null')
        self.context.set_offline(True)
        self.page.reload()
        self.page.wait_for_selector('#stage[aria-busy="false"]')
        self.shape('square')
        self.assertAlmostEqual(self.active.evaluate('el => el.offsetWidth / el.offsetHeight'), 1, delta=.01)
        self.page.locator('#physicsBtn').click()
        self.assertTrue(self.page.locator('.physics-dialog').is_visible())


if __name__ == '__main__':
    unittest.main(verbosity=2)
