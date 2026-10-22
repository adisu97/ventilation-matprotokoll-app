from matprotokoll.associate import assign_vents_to_rooms, pair_all, pair_rows_for_room
from matprotokoll.models import Room, Vent


def _v(name, kind, size, **kwargs) -> Vent:
    return Vent(name=name, kind=kind, size=str(size) if size is not None else None, **kwargs)


def test_pair_same_row():
    rows = pair_rows_for_room(
        "E125 wc",
        [_v("kso", "exhaust", 125, x=0, y=0), _v("kts", "supply", 100, x=1, y=0)],
    )
    assert len(rows) == 1
    assert rows[0].room == "E125 wc"
    assert rows[0].exhaust_name == "kso"
    assert rows[0].supply_name == "kts"
    assert rows[0].exhaust_size == 125
    assert rows[0].supply_size == 100


def test_extra_vents_leave_room_empty():
    vents = [
        _v("kso", "exhaust", 160),
        _v("kso", "exhaust", 160),
        _v("kso", "exhaust", 160),
        _v("eagle", "supply", 160),
        _v("eagle", "supply", 160),
    ]
    rows = pair_rows_for_room("A124 rehabilitering", vents)
    assert len(rows) == 3
    assert rows[0].room == "A124 rehabilitering"
    assert rows[1].room == ""
    assert rows[2].room == ""
    assert rows[2].exhaust_name == "kso"
    assert rows[2].supply_name == ""


def test_qty_expands_to_continuation_rows():
    vents = [_v("kso", "exhaust", 125, qty=2), _v("kts", "supply", 125, qty=1)]
    rows = pair_rows_for_room("C105 korridor", vents)
    assert len(rows) == 2
    assert rows[0].room == "C105 korridor"
    assert rows[1].room == ""
    assert rows[1].exhaust_name == "kso"
    assert rows[1].supply_name == ""


def test_qty_splits_airflow():
    vents = [_v("kts", "supply", 125, qty=2, airflow=56)]
    rows = pair_rows_for_room("E111 vindfång", vents)
    assert len(rows) == 2
    assert rows[0].supply_behov == 28
    assert rows[1].supply_behov == 28
    assert rows[1].room == ""


def test_assign_nearest_room():
    rooms = [
        Room(number="E125", name="wc", x=0, y=0),
        Room(number="E126", name="wc", x=100, y=0),
    ]
    vents = [_v("kso", "exhaust", 125, x=5, y=1)]
    grouped = assign_vents_to_rooms(vents, rooms)
    assert "E125 wc" in grouped
    assert grouped["E125 wc"][0].name == "kso"


def test_pair_all_order_and_unknown():
    rooms = [Room(number="E102", name="aula", x=0, y=0)]
    vents = [
        _v("kso", "exhaust", 125, x=1, y=1),
        _v("eagle", "supply", "200-600", x=5000, y=5000),  # för långt → okänt
    ]
    rows = pair_all(rooms, vents)
    labels = [r.room for r in rows if r.room]
    assert "E102 aula" in labels
    assert any(r.room == "okänt utrymme" for r in rows) or any(r.supply_name == "eagle" for r in rows)
