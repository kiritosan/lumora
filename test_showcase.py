import functools
import http.server
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
