"""Replays JSON protocols written by the Kotlin engine and checks that the Python engine agrees.

Generate them first:  ./gradlew :rules-core:generateProtocols
"""
import json
from pathlib import Path

import numpy as np
import pytest

from waddeln import engine as E
from waddeln import talk as T
from waddeln.bots import RuleBot, apply_discard_rule
from waddeln.features import encode_action, encode_state, STATE_SIZE, ACTION_FEATURES

PROTOCOLS = Path(__file__).resolve().parents[1] / "protocols" / "protocols.jsonl"


def load_matches():
    if not PROTOCOLS.exists():
        pytest.skip(f"{PROTOCOLS} missing – run ./gradlew :rules-core:generateProtocols")
    with PROTOCOLS.open() as f:
        return [json.loads(line) for line in f]


def dense(sparse, size):
    out = np.zeros(size, dtype=np.float32)
    for i, v in sparse:
        out[i] = v
    return out


def replay(match):
    bot = RuleBot()
    state = None
    checked = {"steps": 0, "bot": 0, "features": 0, "discard": 0, "discard_changed": 0,
               "hints": 0, "schadet": 0, "schlagwechsel": 0, "bluffs": 0}
    for r, rnd in enumerate(match["rounds"]):
        if r == 0:
            state = E.new_match(rnd["dealer"], rnd["deck"], rnd["cut"])
        else:
            state = E.start_next_round(state, rnd["deck"], rnd["cut"])
        ctx = f"seed {match['seed']} round {r}"
        assert state.dealer == rnd["dealer"], ctx
        assert state.scores == rnd["scoresBefore"], ctx
        assert state.hands == rnd["hands"], ctx
        assert state.stock == rnd["stock"], ctx
        assert state.abheber_kritten == rnd["abheberKritten"], ctx
        assert state.geber_kritten == rnd["geberKritten"], ctx
        assert state.geber_card == rnd["geberCard"], ctx

        for i, step in enumerate(rnd["steps"]):
            sctx = f"{ctx} step {i}"
            if "talk" in step:
                replay_talk(state, step, sctx, checked)
                continue
            for p in range(4):
                assert E.legal_actions(state, p) == step["legal"][p], f"{sctx} legal for player {p}"
            p, a = step["p"], step["a"]
            obs = E.observe(state, p)
            allowed = T.allowed_actions(state, p, step["legal"][p])
            if step["bot"]:
                assert bot.choose(obs, allowed) == a, f"{sctx} bot decision"
                checked["bot"] += 1
            if "dr" in step:
                assert apply_discard_rule(obs, allowed, a) == step["dr"], f"{sctx} discard rule"
                checked["discard"] += 1
                checked["discard_changed"] += step["dr"] != a
            if "f" in step:
                np.testing.assert_allclose(encode_state(obs), dense(step["f"], STATE_SIZE), atol=1e-6, err_msg=sctx)
                np.testing.assert_allclose(encode_action(obs, a), dense(step["af"], ACTION_FEATURES), atol=1e-6, err_msg=sctx)
                checked["features"] += 1
            trick_count = len(state.completed_tricks)
            E.apply(state, p, a)
            after = step["after"]
            assert E.PHASE_NAMES[state.phase] == after["phase"], sctx
            assert state.scores == after["scores"], sctx
            assert state.game_value == after["gameValue"], sctx
            assert state.tricks_won == after["tricksWon"], sctx
            winner = state.completed_tricks[-1][1] if len(state.completed_tricks) > trick_count else -1
            assert winner == after["trickWinner"], sctx
            assert (state.pending_schub if state.pending_schub is not None else -1) == after["pending"], sctx
            assert (state.last_schub_team if state.last_schub_team is not None else -1) == after["lastSchubTeam"], sctx
            laid = None if state.laid_out is None else [sorted(s) for s in state.laid_out]
            assert laid == after["laidOut"], sctx
            checked["steps"] += 1

        res = rnd["result"]
        assert state.result == (res["winningTeam"], res["points"], res["reason"]), ctx
        assert state.scores == rnd["scoresAfter"], ctx
    assert state.scores == match["finalScores"]
    assert E.match_winner(state) == match["winner"]
    return checked


def replay_talk(state, step, ctx, checked):
    """Partner hint, "Schadder?", Schlagwechsel or their answers: the Python heuristics must say the same."""
    p, kind = step["p"], step["talk"]
    if kind == "hint":
        assert T.partner_hint(E.observe(state, p)) == step["v"], f"{ctx} partner hint"
        assert T.hint_trick(E.observe(state, p)) == step["t"], f"{ctx} partner hint trick"
        E.give_hint(state, p, step["v"])
        checked["hints"] += 1
    elif kind == "ask":
        if state.haubi not in state.hands[p]:
            assert T.can_bluff(state, p), f"{ctx} bluff"
            checked["bluffs"] += 1
        E.ask_schadet(state, p)
    elif kind == "answer":
        assert T.schadet_answer(E.observe(state, p)) == step["v"], f"{ctx} Schadder answer"
        E.answer_schadet(state, p, step["v"])
        checked["schadet"] += 1
    elif kind == "swap":
        assert E.can_propose_schlagwechsel(state, p), f"{ctx} Schlagwechsel"
        assert T.proposes_schlagwechsel(E.observe(state, p)) == bool(step["v"]), f"{ctx} Schlagwechsel proposal"
        if step["v"]:
            E.propose_schlagwechsel(state, p)
    elif kind == "swapAnswer":
        assert T.accepts_schlagwechsel(E.observe(state, p)) == bool(step["v"]), f"{ctx} Schlagwechsel answer"
        E.answer_schlagwechsel(state, p, bool(step["v"]))
        checked["schlagwechsel"] += 1
    else:
        raise AssertionError(f"{ctx} unknown talk {kind}")


def test_protocols_replay_identically():
    totals = {"steps": 0, "bot": 0, "features": 0, "discard": 0, "discard_changed": 0,
              "hints": 0, "schadet": 0, "schlagwechsel": 0, "bluffs": 0, "laid_out": 0, "kritte_haubi": 0, "geber_kritten": 0, "matches": 0}
    for match in load_matches():
        checked = replay(match)
        for k in checked:
            totals[k] += checked[k]
        totals["matches"] += 1
        totals["laid_out"] += sum(1 for r in match["rounds"] for s in r["steps"]
                                  if "after" in s and s["after"]["laidOut"] is not None)
        totals["geber_kritten"] += sum(1 for r in match["rounds"] if r["geberKritten"] > 0)
        for r in match["rounds"]:
            codes = [s["a"] for s in r["steps"] if "a" in s][:2]
            if len(codes) == 2 and codes[0] < 8 and 8 <= codes[1] < 12:
                totals["kritte_haubi"] += E.card(codes[1] - 8, codes[0]) in E.KRITTE_SET
    print(totals)
    assert totals["matches"] >= 100
    assert totals["bot"] > 1000 and totals["features"] > 1000
    assert totals["laid_out"] > 0, "protocols should cover gespannt vs gespannt"
    assert totals["kritte_haubi"] > 0, "protocols should cover a Kritte as Haubi"
    assert totals["discard_changed"] > 100, "protocols should cover the Abwurf-Regel changing a card"
    assert totals["hints"] > 100, "protocols should cover partner hints"
    assert totals["schadet"] > 20, "protocols should cover Schadder"
    assert totals["schlagwechsel"] > 50, "protocols should cover the Schlagwechsel"
    assert totals["bluffs"] > 5, "protocols should cover bluffing with Schadder"
    assert totals["geber_kritten"] > 0, "protocols should cover the dealer taking Kritten under the rest"
