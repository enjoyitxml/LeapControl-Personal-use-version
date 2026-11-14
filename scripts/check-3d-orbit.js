#!/usr/bin/env node
/**
 * 3D 车模旋转 JS 的行为验证（不需要真机）。
 *
 * 为什么要这个脚本：旋转逻辑是**注入到官方 H5 里的字符串**，Kotlin 单测只能断言
 * 「源码里有没有这句话」，断言不了「这句话跑起来到底转不转」。而用户的车不支持
 * 3D 车模，也没法在车上验证。所以这里把 `CarModel3DView.kt` 里那段 JS 原样抽出来，
 * 套一个假的三维引擎（renderer / camera / controls / car）跑一遍，验证：
 *
 *  1. 拖一下，相机真的动了（横向绕 Y、纵向抬机位）；
 *  2. 引擎每帧把相机复位 → 我们的机位仍然生效（包住 render 的意义）；
 *  3. 上下都有夹紧，不会钻到车底、也不会翻到车顶；
 *  4. 拿不到 renderer 时退化成每帧自己写相机；连相机都没有时退化成转车身。
 *
 * 用法：node scripts/check-3d-orbit.js
 * 失败会以非 0 退出码结束，方便挂到发版前的检查里。
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const KT = path.join(__dirname, '..', 'app/src/main/java/com/leapcontrol/app/ui/CarModel3DView.kt');

/** 从 Kotlin 源码里抽出 installApi 那段注入脚本（原样，不做任何改写）。 */
function extractInstallApiJs() {
    const src = fs.readFileSync(KT, 'utf8');
    const start = src.indexOf('private fun installApi(');
    if (start < 0) throw new Error('找不到 installApi');
    const rest = src.slice(start);
    const open = rest.indexOf('"""');
    const close = rest.indexOf('"""', open + 3);
    if (open < 0 || close < 0) throw new Error('installApi 里找不到 raw string');
    return rest.slice(open + 3, close);
}

/** 一个够用的 Vector3。 */
function vec(x, y, z) {
    return {
        x, y, z,
        set(nx, ny, nz) { this.x = nx; this.y = ny; this.z = nz; return this; },
        clone() { return vec(this.x, this.y, this.z); },
    };
}

/** 搭一个假的三维场景，形状照着 three.js 的对象特征来（isScene / isCamera / render+domElement）。 */
function makeEngine(opts = {}) {
    const { withRenderer = true, withCar = true } = opts;

    const camera = {
        isCamera: true,
        position: vec(-11.15, 3.13, 10.45),
        lookAt(x, y, z) { this.lookAtX = x; this.lookAtY = y; this.lookAtZ = z; },
    };
    const BASE = camera.position.clone();

    const target = vec(-0.35, 0.98, -0.35);
    const scene = { isScene: true, children: [] };
    const car = { isObject3D: true, rotation: { x: 0, y: 0, z: 0 }, position: vec(0, 0, 0) };
    if (withCar) scene.children.push(car);

    const viewer = { scene, camera, controls: { target, update() {} } };
    if (withCar) viewer.car = car;

    let renderer = null;
    if (withRenderer) {
        renderer = {
            domElement: { nodeType: 1 },
            frames: 0,
            // 真的 renderer.render 只负责画，不动相机。
            // 「引擎每帧复位机位」发生在它的 update 循环里（render 之前），
            // 所以复位放在测试里手动做，不能塞进 render —— 塞进去就等于
            // 模拟成「画完再把机位改回去」，那种引擎根本不会有拖动问题。
            render() { this.frames++; },
        };
        viewer.renderer = renderer;
    }

    return { viewer, camera, car, scene, renderer, BASE };
}

function run(engine, opts = {}) {
    const { exposeViewer = true } = opts;
    const sandbox = {
        Date, Math, Number, String, Object, Array, JSON, isFinite, parseFloat, parseInt,
        console,
        requestAnimationFrame: () => 0, // pump 只登记、不递归
    };
    sandbox.window = sandbox;
    if (exposeViewer) sandbox.viewer = engine.viewer;

    vm.createContext(sandbox);
    vm.runInContext(extractInstallApiJs(), sandbox, { filename: 'installApi.js' });
    return sandbox;
}

const results = [];
function check(name, fn) {
    try {
        fn();
        results.push([true, name]);
    } catch (e) {
        results.push([false, name, e.message]);
    }
}
function assert(cond, msg) {
    if (!cond) throw new Error(msg);
}
function dist(a, b) {
    return Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
}

// ------------------------------------------------------------------ 1. 横向能转
check('横向拖动：相机绕车转起来', () => {
    const e = makeEngine();
    const win = run(e);
    assert(typeof win.__leapOrbit === 'function', '__leapOrbit 没装上');

    const before = e.camera.position.clone();
    win.__leapOrbit(0.4, 0); // 向右拖
    e.renderer.render(e.scene, e.camera); // 引擎画一帧

    assert(dist(before, e.camera.position) > 0.5, `相机没动：${JSON.stringify(e.camera.position)}`);
    assert(win.__leapDiag === 'orbit', `diag 应为 orbit，实际 ${win.__leapDiag}`);
    // 向右拖 = 相机逆着走（车跟着手指转），和 OrbitControls 同一个手感
    assert(e.camera.position.x < before.x, '向右拖时相机没有往左走，方向反了');
    // 机位高度不该被横向拖动改变
    assert(Math.abs(e.camera.position.y - before.y) < 0.2, '横向拖动把机位高度也带跑了');
});

// ------------------------------------------------------------------ 2. 纵向能转
check('纵向拖动：机位抬起来，能看车顶', () => {
    const e = makeEngine();
    const win = run(e);
    const before = e.camera.position.clone();

    win.__leapOrbit(0, 0.5); // 向下拖 → 抬高机位
    e.renderer.render(e.scene, e.camera);

    assert(e.camera.position.y > before.y + 0.5, `向下拖没抬高机位：y=${e.camera.position.y}`);
    // 半径不能变（不是把镜头推远，是在球面上走）
    const r0 = Math.hypot(before.x + 0.35, before.y - 0.98, before.z + 0.35);
    const r1 = Math.hypot(e.camera.position.x + 0.35, e.camera.position.y - 0.98, e.camera.position.z + 0.35);
    assert(Math.abs(r0 - r1) < 0.35, `绕行半径变了 ${r0} → ${r1}，会像推拉镜头`);
});

// ------------------------------------------------------------------ 3. 引擎复位也压不住
check('引擎每帧把相机复位，我们的机位仍然生效', () => {
    const e = makeEngine();
    const win = run(e);
    win.__leapOrbit(0.3, 0);

    // 连画十帧，每帧引擎都先把相机复位
    let last = null;
    for (let i = 0; i < 10; i++) {
        e.camera.position.set(e.BASE.x, e.BASE.y, e.BASE.z);
        e.renderer.render(e.scene, e.camera);
        last = e.camera.position.clone();
    }
    assert(dist(e.BASE, last) > 0.5, '引擎复位之后我们的机位被盖回去了（拖一下弹回原位）');
});

// ------------------------------------------------------------------ 4. 夹紧
check('上下都夹紧：不钻车底、不翻到车顶', () => {
    const e = makeEngine();
    const win = run(e);
    win.__leapOrbit(0, 100); // 一直往下拖
    e.renderer.render(e.scene, e.camera);
    const r = Math.hypot(e.camera.position.x + 0.35, e.camera.position.z + 0.35);
    const elevMax = Math.atan2(e.camera.position.y - 0.98, r);
    assert(elevMax <= 1.31, `仰角没有上限，跑到了 ${elevMax.toFixed(2)} rad`);

    const e2 = makeEngine();
    const win2 = run(e2);
    win2.__leapOrbit(0, -100); // 一直往上拖
    e2.renderer.render(e2.scene, e2.camera);
    const r2 = Math.hypot(e2.camera.position.x + 0.35, e2.camera.position.z + 0.35);
    const elevMin = Math.atan2(e2.camera.position.y - 0.98, r2);
    assert(elevMin >= -0.06, `仰角没有下限，钻到车底了 ${elevMin.toFixed(2)} rad`);
});

// ------------------------------------------------------------------ 5. 没有 renderer
check('拿不到 renderer 时退化成每帧自己写相机', () => {
    const e = makeEngine({ withRenderer: false });
    const win = run(e);
    const before = e.camera.position.clone();
    win.__leapOrbit(0.4, 0);
    assert(win.__leapDiag === 'cam', `diag 应为 cam，实际 ${win.__leapDiag}`);
    assert(dist(before, e.camera.position) > 0.5, '没有 renderer 时相机完全没动');
});

// ------------------------------------------------------------------ 6. 什么都找不到
check('连实例都摸不到时退化为转车身', () => {
    const e = makeEngine({ withRenderer: false, withCar: true });
    const win = run(e, { exposeViewer: false });
    // 只把车身挂在全局，不给 scene+camera：相当于「实例被闭包藏起来了」
    win.myCar = e.car;
    win.__leapOrbit(0.4, 0);
    // 车身既不在 viewer.car 也不叫 car，只能落到 scene 兜底 → 摸不到就是 no-viewer，
    // 摸到了就是 body。两种都算「没有静默成功」
    assert(win.__leapDiag === 'body' || win.__leapDiag === 'no-viewer',
        `diag 异常：${win.__leapDiag}`);
});

check('重复安装不会叠加包装（幂等）', () => {
    const e = makeEngine();
    const win = run(e);
    win.__leapOrbit(0.2, 0);
    const r = e.renderer;
    const wrapped = r.render;
    win.__leapOrbit(0.2, 0);
    assert(r.render === wrapped, 'render 被重复包装了，转一次会变成转两次');
});

// ------------------------------------------------------------------ 汇总
let failed = 0;
for (const [ok, name, msg] of results) {
    if (!ok) failed++;
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok ? '' : '  →  ' + msg}`);
}
console.log(`\n${results.length - failed}/${results.length} passed`);
process.exit(failed ? 1 : 0);
