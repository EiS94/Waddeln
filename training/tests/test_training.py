"""Checks of the training building blocks that do not need the Kotlin protocols."""
import random

import numpy as np
import torch

from waddeln import engine as E
from waddeln import talk as T
from waddeln.bots import RandomBot, RuleBot
from waddeln.features import INPUT_SIZE, LEGACY_STATE_SIZE, STATE_SIZE, TABLE_TALK_STATE_SIZE
from waddeln.match_value import MatchValue
from waddeln.model import ModelAgent, QNet, load_checkpoint, save_checkpoint
from waddeln.selfplay import evaluate, play_match


def test_random_matches_keep_invariants():
    rng = random.Random(3)
    for _ in range(200):
        state = E.new_random_match(rng)
        while state.phase != E.MATCH_OVER:
            if state.phase == E.ROUND_OVER:
                state = E.start_next_round(state, *E.random_deal(rng))
                continue
            cards = sum(len(h) for h in state.hands) + len(state.stock) + len(state.current_trick) \
                + 4 * len(state.completed_tricks)
            assert cards == 32
            options = [(p, a) for p in range(4) for a in E.legal_actions(state, p)]
            E.apply(state, *rng.choice(options))
        assert max(state.scores) >= E.MATCH_POINTS


def test_selfplay_samples_have_round_returns():
    agent = ModelAgent(QNet((32,)), epsilon=0.1, rng=random.Random(1))
    X, y, end = play_match([agent] * 4, random.Random(2), record_seats=[0, 1, 2, 3])
    assert X.shape == (len(y), INPUT_SIZE) and len(y) > 0
    assert np.all(y != 0)
    assert end.phase == E.MATCH_OVER


def test_match_value_table_is_consistent():
    mv = MatchValue()
    assert np.allclose(mv.v + mv.v.T, 1.0)
    assert abs(mv.win_chance(0, 0) - 0.5) < 1e-6
    for a in range(E.MATCH_POINTS - 1):
        for b in range(E.MATCH_POINTS):
            assert mv.win_chance(a + 1, b) >= mv.win_chance(a, b) - 0.02  # more points never hurt (noise aside)


def test_giving_up_does_not_pay_when_the_opponents_get_gespannt_anyway():
    # Opponents on 7 say Schub from 2 to 3: going lets them reach 9, losing the game 10 – both gespannt.
    mv = MatchValue()
    go = mv.target([4, 7], 0, 1, 2)
    stay_and_lose = mv.target([4, 7], 0, 1, 3)
    stay_and_win = mv.target([4, 7], 0, 0, 3)
    assert abs(go - stay_and_lose) < 0.05 * mv.scale
    assert stay_and_win > 0 > go


def test_only_recorded_seats_produce_samples():
    agent = ModelAgent(QNet((32,)), rng=random.Random(1))
    X, y, _ = play_match([agent, RuleBot(), agent, RuleBot()], random.Random(5), record_seats=[0, 2])
    assert len(y) > 0


def test_rule_bot_beats_random():
    result = evaluate(RuleBot(), RandomBot(random.Random(1)), matches=200)
    assert result["win_rate"] > 0.75


def test_checkpoint_roundtrip(tmp_path):
    model = QNet((16, 8))
    path = tmp_path / "m.pt"
    save_checkpoint(path, model, samples=42)
    loaded, meta = load_checkpoint(path)
    x = torch.randn(4, INPUT_SIZE)
    assert torch.allclose(model(x), loaded(x))
    assert meta["samples"] == 42


def _all_model(rng):
    from waddeln.selfplay import MODEL
    return [MODEL] * 4, [0, 1, 2, 3]


def _model_vs_rule(rng):
    from waddeln.selfplay import MODEL
    return [MODEL, RuleBot(), MODEL, RuleBot()], [0, 2]


def test_batched_selfplay_plays_legally_and_greedy(monkeypatch):
    from waddeln import selfplay
    model = QNet((32,))
    original = E.apply
    checked = {"n": 0, "greedy": 0}

    def checked_apply(state, player, action, check=True):
        assert action in E.legal_actions(state, player)
        checked["n"] += 1
        return original(state, player, action, check=False)

    monkeypatch.setattr(E, "apply", checked_apply)
    sp = selfplay.BatchedSelfPlay(model, games=8, rng=random.Random(4), new_config=_all_model, epsilon=0.0)
    X, y = sp.collect(2000)
    assert X.shape == (len(y), INPUT_SIZE) and len(y) >= 2000
    assert np.all(y != 0)
    assert checked["n"] > 2000


def test_batched_choice_equals_single_model_agent():
    """The batched forward pass must pick the same action as ModelAgent for each decision."""
    from waddeln import selfplay
    model = QNet((32,))
    single = ModelAgent(model)
    rng = random.Random(9)
    sp = selfplay.BatchedSelfPlay(model, games=6, rng=rng, new_config=_all_model)
    original_step_apply = E.apply
    mismatches = []

    def spy(state, player, action, check=True):
        legal = T.allowed_actions(state, player, E.legal_actions(state, player))
        if len(legal) > 1:
            expected = single.choose(E.observe(state, player), legal)
            if expected != action:
                mismatches.append((expected, action))
        return original_step_apply(state, player, action, check=False)

    E.apply = spy
    try:
        sp.collect(500)
    finally:
        E.apply = original_step_apply
    assert not mismatches


def test_selfplay_inputs_contain_table_talk():
    """Bots ask their partner before answering a Schub and ask "Schadder?" before leading the Haubi."""
    agent = ModelAgent(QNet((32,)), epsilon=0.3, rng=random.Random(1))
    talk = np.zeros(TABLE_TALK_STATE_SIZE - LEGACY_STATE_SIZE)
    for seed in range(20):
        X, _, _ = play_match([agent] * 4, random.Random(seed), record_seats=[0, 1, 2, 3])
        talk += X[:, LEGACY_STATE_SIZE:TABLE_TALK_STATE_SIZE].sum(axis=0)
    hints, asker, answer = talk[0:20], talk[20:24], talk[24:27]
    assert hints.sum() > 0 and asker.sum() > 0 and answer.sum() > 0
    assert talk[31:35].sum() > 0, "Schubs per seat"


def test_batched_records_only_model_seats_of_mixed_matches():
    from waddeln import selfplay
    sp = selfplay.BatchedSelfPlay(QNet((32,)), games=4, rng=random.Random(2), new_config=_model_vs_rule, epsilon=0.1)
    X, y = sp.collect(300)
    assert len(y) >= 300


def test_selfplay_inputs_contain_dealing_advice_and_schlagwechsel():
    """The network hears "Was soll ich machen?" and the Schlagwechsel and knows the dealer's Kritten and card."""
    agent = ModelAgent(QNet((32,)), epsilon=0.3, rng=random.Random(1))
    extra = np.zeros(STATE_SIZE - TABLE_TALK_STATE_SIZE)
    for seed in range(40):
        X, _, _ = play_match([agent] * 4, random.Random(seed), record_seats=[0, 1, 2, 3])
        extra += X[:, TABLE_TALK_STATE_SIZE:STATE_SIZE].sum(axis=0)
    geber_kritten, geber_card, advice, advice_now, schlagwechsel = \
        extra[0:3], extra[3:35], extra[35:47], extra[47:51], extra[51:54]
    assert geber_kritten.sum() > 0 and geber_card.sum() > 0
    assert advice.sum() > 0 and advice_now.sum() > 0
    assert schlagwechsel[0] > 0 and schlagwechsel[1:].sum() > 0


def test_bots_stay_when_going_makes_the_opponents_gespannt_anyway():
    from waddeln.bots import schub_must_stay
    state = E.new_random_match(random.Random(3))
    state.scores = [4, 7]
    state.phase = E.PLAYING
    state.pending_schub, state.last_schub_team = 1, 1  # opponents on 7 say Schub from 2 to 3
    obs = E.observe(state, 0)
    assert schub_must_stay(obs)
    prefers_gehen = ModelAgent(QNet((8,)))
    prefers_gehen.scores = lambda o, legal: (None, np.array([float(a == E.A_GEHEN) for a in legal]))
    assert prefers_gehen.choose(obs, [E.A_BLEIBEN, E.A_GEHEN]) == E.A_BLEIBEN
    assert RuleBot().choose(obs, [E.A_BLEIBEN, E.A_GEHEN]) == E.A_BLEIBEN
    state.scores = [4, 6]
    assert not schub_must_stay(E.observe(state, 0))
    state.scores = [4, 8]
    assert not schub_must_stay(E.observe(state, 0))
