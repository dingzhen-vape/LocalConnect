from enum import Enum, auto


class States(Enum):
    IDLE = auto()
    UP_LOADING = auto()
    DOWN_LOADING = auto()


class Modes(Enum):
    THUMB = auto()
    FULL = auto()