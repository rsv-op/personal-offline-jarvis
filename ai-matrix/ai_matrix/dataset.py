"""The teaching corpus shipped with ai-matrix.

Every intent here maps onto an :class:`com.example.voiceassistant.nlp.AppIntent`
on the Android side (see ``INTENT_ACTIONS``). Keep the wording short and varied —
this is what the on-device featurizer learns from, together with anything the user
teaches later on the phone.
"""

from __future__ import annotations

from typing import Dict, List, Tuple

from .model import UNKNOWN_LABEL

#: Intent label -> the AppIntent action the Kotlin runtime maps it to.
INTENT_ACTIONS: Dict[str, str] = {
    "FLASHLIGHT_ON": "ToggleFlashlight(isOn = true)",
    "FLASHLIGHT_OFF": "ToggleFlashlight(isOn = false)",
    "ALARM_SET": "SetAlarm(time)",
    "TIMER_SET": "SetTimer(duration)",
    "DELETE_FILE": "DeleteFile(fileName)",
    "DEVICE_STATUS": "StatusReport()",
    UNKNOWN_LABEL: "UnknownIntent",
}

#: Intent label -> example utterances.
UTTERANCES: Dict[str, Tuple[str, ...]] = {
    "FLASHLIGHT_ON": (
        "turn on the flashlight",
        "turn the torch on",
        "switch on the flashlight",
        "flashlight on",
        "light up the torch",
        "can you switch the torch on",
        "i need the flashlight on",
        "enable the flashlight",
        "put the flashlight on",
        "turn on torch please",
        "jarvis turn on the flashlight",
        "start the flashlight",
        "flashlight please",
        "switch on the light on my phone",
        "light please",
        "i need some light",
        "turn my torch on",
        "make the flashlight work",
        "power on the torch",
        "flashlight on now",
        "can you turn the light on",
        "switch the torch on for me",
        "give me light from the phone",
        "torch on",
        "i cannot see turn the light on",
    ),
    "FLASHLIGHT_OFF": (
        "turn off the flashlight",
        "turn the torch off",
        "switch off the flashlight",
        "flashlight off",
        "kill the torch",
        "can you switch the torch off",
        "disable the flashlight",
        "put the flashlight off",
        "turn off torch please",
        "jarvis turn off the flashlight",
        "stop the flashlight",
        "flashlight is not needed anymore",
        "turn my torch off",
        "shut the flashlight down",
        "power off the torch",
        "flashlight off now",
        "can you turn the light off",
        "switch the torch off for me",
        "i do not need the light anymore",
        "torch off",
        "turn the phone light off",
        "no more light",
        "lights out",
        "dim the torch off",
    ),
    "ALARM_SET": (
        "set an alarm",
        "set alarm for seven",
        "wake me up at six thirty",
        "set an alarm for tomorrow morning",
        "can you set an alarm",
        "alarm please",
        "remind me to wake up at five",
        "i want an alarm at nine",
        "create an alarm for noon",
        "set my alarm clock",
        "wake me up early",
        "set an alarm for eight pm",
        "alarm for six in the morning",
        "please wake me at seven",
        "put an alarm for ten",
        "i need to wake up at four am",
        "set a wake up alarm",
        "alarm at half past six",
        "make an alarm for tomorrow",
        "wake me up tomorrow at seven",
        "set the alarm for midnight",
        "jarvis set an alarm for five",
        "i want to be up by six",
        "alarm for work at seven thirty",
    ),
    "TIMER_SET": (
        "set a timer for ten minutes",
        "start a countdown",
        "timer for five minutes",
        "set a timer",
        "start a timer for thirty seconds",
        "countdown timer please",
        "give me a fifteen minute timer",
        "start the timer for two hours",
        "timer of twenty minutes",
        "set a timer for one minute",
        "count down two minutes",
        "i need a timer for ten minutes",
        "start counting down from five minutes",
        "timer for half an hour",
        "put a timer on for three minutes",
        "set a kitchen timer",
        "remind me in five minutes",
        "timer ten minutes please",
        "begin a countdown for one hour",
        "timer for ninety seconds",
        "jarvis set a timer for two minutes",
        "start timer",
    ),
    "DELETE_FILE": (
        "delete that file",
        "remove the file",
        "delete my notes file",
        "erase this file",
        "get rid of the file",
        "delete the download",
        "remove file named report",
        "delete this document",
        "wipe the file",
        "delete the file i saved",
        "throw away this file",
        "remove that document",
        "delete file",
        "clean up this file",
        "erase the document please",
        "delete the saved file",
        "jarvis delete this file",
        "remove the downloaded file",
        "delete my draft",
        "get rid of that document",
        "permanently delete this file",
    ),
    "DEVICE_STATUS": (
        "what is my battery level",
        "how much battery is left",
        "battery status",
        "is my phone charging",
        "how is the battery doing",
        "device status",
        "check the battery",
        "are you online",
        "what model are you",
        "what can you do",
        "tell me about yourself",
        "how much storage is left",
        "battery percentage",
        "do i need to charge my phone",
        "how much memory is free",
        "what is my phone status",
        "are you listening offline",
        "which voice model are you using",
        "how long can the battery last",
        "show me my device information",
        "is the assistant working offline",
        "what is your name",
    ),
    UNKNOWN_LABEL: (
        "what is the capital of france",
        "tell me a joke",
        "explain quantum physics",
        "how do i cook pasta",
        "who won the world cup",
        "what is the weather tomorrow",
        "translate good morning to hindi",
        "write a poem about the sea",
        "what does this word mean",
        "how many people live in india",
        "summarize this article",
        "help me plan my week",
        "why is the sky blue",
        "give me a recipe for bread",
        "what time is it in tokyo",
        "tell me something interesting",
        "who is the president of brazil",
        "how tall is mount everest",
        "what is the square root of ninety",
        "recommend a book about history",
        "how do i learn guitar",
        "what is photosynthesis",
        "name the largest ocean",
        "how far is the moon",
        "can you sing a song",
        "what should i eat for dinner",
        "explain how a car engine works",
        "what is the meaning of life",
        "how do i improve my sleep",
        "what is the population of germany",
    ),
}


def load_dataset() -> List[Tuple[str, str]]:
    """Flatten :data:`UTTERANCES` into ``(text, label)`` pairs."""

    return [(text, label) for label, texts in UTTERANCES.items() for text in texts]


def labels() -> List[str]:
    """Stable label ordering used by training and export."""

    return list(UTTERANCES.keys())


def split_dataset(
    holdout_per_label: int = 4,
) -> Tuple[List[Tuple[str, str]], List[Tuple[str, str]]]:
    """Deterministically hold out every ``k``-th example so both sets look alike.

    Taking a contiguous tail of each list (the naive approach) biases the holdout
    towards whatever the author typed last; an interleaved stride samples the whole
    phrasing range instead, which makes the reported holdout accuracy meaningful.
    """

    train: List[Tuple[str, str]] = []
    test: List[Tuple[str, str]] = []
    for label, texts in UTTERANCES.items():
        ordered = list(texts)
        stride = max(2, len(ordered) // max(1, holdout_per_label))
        test.extend(
            (text, label) for index, text in enumerate(ordered) if index % stride == stride - 1
        )
        train.extend(
            (text, label) for index, text in enumerate(ordered) if index % stride != stride - 1
        )
    return train, test
