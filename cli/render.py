"""Maid CLI · 输出渲染（人类可读）。

原则：**给人看的简洁，给机器看的完整** —— 默认渲染成人能扫一眼的格式，`--json` 出原始数据。
"""
from __future__ import annotations

import json

DIM = "\033[2m"
BOLD = "\033[1m"
GREEN = "\033[32m"
RED = "\033[31m"
YELLOW = "\033[33m"
CYAN = "\033[36m"
RESET = "\033[0m"


def _p(s: str = "") -> None:
    print(s)


def raw(obj) -> None:
    _p(json.dumps(obj, ensure_ascii=False, indent=2))


def ok_badge(ok: bool) -> str:
    return f"{GREEN}✓{RESET}" if ok else f"{RED}✗{RESET}"


def render(name: str, resp: dict, json_mode: bool = False) -> None:
    """按指令名分发渲染。未识别的指令退回通用渲染。"""
    if json_mode:
        raw(resp)
        return
    if not resp.get("ok", False):
        data = resp.get("data") or {}
        # ★ 作业失败（协议层 ok=false，但那是"活儿没干成"，不是请求错）—— 走作业渲染
        if data.get("result"):
            _job_report(name, data)
            return
        render_error(resp)
        return
    data = resp.get("data") or {}
    # 作业完成（history 条目）也走作业渲染
    if data.get("result") and "id" in data and ("ok_count" in data or "total" in data):
        _job_report(name, data)
        return
    fn = _RENDERERS.get(name)
    if fn:
        fn(data)
    else:
        _generic(name, data)


def render_error(resp: dict) -> None:
    code = resp.get("code", "?")
    msg = resp.get("message", "")
    hint = resp.get("hint")
    _p(f"{RED}✗ [{code}]{RESET} {msg}")
    if hint:
        _p(f"  {DIM}→ {hint}{RESET}")
    extra = {k: v for k, v in resp.items() if k not in ("ok", "code", "message", "hint")}
    if extra:
        _p(f"  {DIM}{json.dumps(extra, ensure_ascii=False)}{RESET}")


def _generic(name: str, data: dict) -> None:
    _p(json.dumps(data, ensure_ascii=False, indent=2))


# ────────────────────────── 各家渲染 ──────────────────────────

def _maids(d: dict) -> None:
    ms = d.get("maids", [])
    if not ms:
        _p("（没有女仆）")
        return
    _p(f"{BOLD}共 {len(ms)} 只{RESET}")
    for m in ms:
        flags = []
        if m.get("awake"):
            flags.append(f"{GREEN}已唤醒{RESET}")
        elif m.get("loaded"):
            flags.append(f"{YELLOW}在世界里{RESET}")
        else:
            flags.append(f"{DIM}未加载{RESET}")
        if m.get("stale"):
            flags.append(f"{DIM}失联{RESET}")
        pos = m.get("pos")
        line = f"  {BOLD}{m.get('tag')}{RESET}  {m.get('name')}"
        if pos:
            line += f"  {DIM}@{pos[0]},{pos[1]},{pos[2]}{RESET}"
        line += "  " + " ".join(flags)
        _p(line)


def _sense(d: dict) -> None:
    # 按域猜着渲染（服务端的域返回结构不同）
    if "health" in d or "favor" in d:
        _sense_self(d)
    elif "backpack" in d or "hands" in d or "backpack_items" in d:
        _sense_inventory(d)
    else:
        _p(json.dumps(d, ensure_ascii=False, indent=2))


def _sense_self(d: dict) -> None:
    h = d.get("health", {})
    f = d.get("favor", {})
    p = d.get("pos", {})
    ex = d.get("exp") or {}
    _p(f"  {BOLD}血量{RESET} {h.get('current')}/{h.get('max')}   "
       f"{BOLD}好感{RESET} {f.get('value')}(Lv{f.get('level')})   "
       f"{BOLD}经验{RESET} {ex.get('points')}(Lv{ex.get('level')})")
    _p(f"  {BOLD}位置{RESET} {p.get('xyz')}  yaw={_r(p.get('yaw'))} pitch={_r(p.get('pitch'))}  "
       f"{DIM}{p.get('dimension')}{RESET}")
    eff = d.get("effects") or []
    if eff:
        _p(f"  {BOLD}状态{RESET} " + ", ".join(f"{e['id']}×{e['amplifier']+1}({e['duration']}t)" for e in eff))
    if d.get("riding"):
        _p(f"  {BOLD}骑着{RESET} {d['riding'].get('type')} (id={d['riding'].get('entity_id')})")


def _sense_inventory(d: dict) -> None:
    hands = d.get("hands") or {}
    main = (hands.get("main") or {}).get("item")
    off = (hands.get("off") or {}).get("item")
    _p(f"  {BOLD}主手{RESET} {main or chr(45)}   {BOLD}副手{RESET} {off or chr(45)}")
    bp = d.get("backpack") or {}
    if bp:
        _p(f"  {BOLD}背包{RESET} {bp.get('type') or '无'} ({bp.get('slots', 0)} 格)")
    items = d.get("backpack_items") or []
    for it in items:
        # ★ 打 inv:N 槽号（手在前），**不是背包内下标** —— 两者差 2，照抄下标会搬到错的格
        #   （2026-10-23 实测：看文本 [1] 敲 move from=inv:1 → "那一格是空的"）
        slot = it.get("slot")
        tag = f"inv:{slot}" if slot is not None else f"[{it.get('index')}]"
        _p(f"    {tag} {it.get('item')} ×{it.get('count')}")
    armor = d.get("armor") or {}
    worn = {k: (v or {}).get("item") for k, v in armor.items() if v}
    if worn:
        _p(f"  {BOLD}护甲{RESET} " + ", ".join(f"{k}={v}" for k, v in worn.items()))


def _sense_state(d: dict) -> None:
    a = d.get("action")
    if a:
        _p(f"  {BOLD}正在做{RESET} [{a.get('id')}] {a.get('op')} {a.get('phase')} "
           f"{DIM}progress={a.get('progress')}{RESET}")
    else:
        _p(f"  {DIM}（空闲）{RESET}")
    q = d.get("queue") or []
    if q:
        _p(f"  {BOLD}排队{RESET} " + ", ".join(f"[{x.get('id')}]{x.get('op')}" for x in q))
    hist = d.get("history") or []
    if hist:
        _p(f"  {BOLD}历史{RESET}")
        for h in hist:
            _p(f"    {ok_badge(h.get('ok'))} [{h.get('id')}] {h.get('op')}"
               + (f"/{h.get('name')}" if h.get("name") else "")
               + f"  {DIM}{h.get('result')} ok={h.get('ok_count')}/{h.get('total')}{RESET}"
               + (f"  {h.get('message')}" if h.get("message") else ""))
            for f in (h.get("failures") or [])[:3]:
                _p(f"      {RED}- {f.get('what')}: {f.get('reason')}{RESET}")


def _job_report(name: str, d: dict) -> None:
    ok = d.get("ok", False)
    res = d.get("result", "?")
    _p(f"  {ok_badge(ok)} {name}  {BOLD}{res}{RESET}  ok={d.get('ok_count')}/{d.get('total')}"
       + (f"  {DIM}{d.get('message')}{RESET}" if d.get("message") else ""))
    for f in (d.get("failures") or [])[:8]:
        _p(f"    {RED}- {f.get('what')}: {f.get('reason')}{RESET}")


def _login(d: dict) -> None:
    if d.get("logged_out") is not None:
        _p(f"  {ok_badge(d['logged_out'])} 已登出")
        return
    _p(f"  {GREEN}✓{RESET} 已登录：{BOLD}{d.get('player')}{RESET}  "
       f"{DIM}key={str(d.get('key'))[:8]}… 有效 {d.get('ttl_seconds', 0)//3600} 小时{RESET}")


def _wake(d: dict) -> None:
    a = d.get("awake")
    _p(f"  {ok_badge(bool(a))} {d.get('message', '')}"
       + (f"  {DIM}{d.get('maid_name')} ({str(d.get('maid_uuid'))[:8]}){RESET}" if d.get("maid_uuid") else ""))


def _control(d: dict) -> None:
    _p(f"  {GREEN}✓{RESET} {d.get('message', '')}"
       + (f"  {DIM}清掉 {d.get('cleared_parts', 0)} 个零件{RESET}" if d.get("cleared_parts") else ""))


def _stop(d: dict) -> None:
    _p(f"  {GREEN}✓{RESET} {d.get('message', '已停')}")


def _mode(d: dict) -> None:
    _p(f"  {GREEN}✓{RESET} 已切到 {BOLD}{d.get('name')}{RESET}  {DIM}{d.get('mode')}{RESET}")


def _blocks(d: dict) -> None:
    if "counts" in d:
        for k, v in sorted(d["counts"].items(), key=lambda x: -x[1]):
            _p(f"  {v:>6}  {k}")
        return
    bs = d.get("blocks")
    if bs is not None:
        _p(f"  {DIM}{len(bs)} 格{RESET}")
        for b in bs[:80]:
            st = f"[{b['state']}]" if b.get("state") else ""
            _p(f"    {b['pos']}  {b['block']}{DIM}{st}{RESET}")
        if len(bs) > 80:
            _p(f"    {DIM}… 还有 {len(bs)-80} 格（用 --json 拿全部）{RESET}")
        return
    _generic("blocks", d)


def _r(v, n=1):
    try:
        return round(float(v), n)
    except (TypeError, ValueError):
        return v


_RENDERERS = {
    "maids": _maids,
    "login": _login,
    "wake": _wake,
    "control": _control,
    "stop": _stop,
    "mode": _mode,
    "blocks": _blocks,
    "sense": _sense,
}
