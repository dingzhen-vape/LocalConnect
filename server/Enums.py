from enum import Enum, auto


class States(Enum):
    IDLE = auto()
    UP_LOADING = auto()

class Modes(Enum):
    FILE = auto()
    THUMB = auto()