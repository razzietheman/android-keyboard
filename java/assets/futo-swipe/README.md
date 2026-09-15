---
license: other
license_name: futo-model-weights-license-1.0
license_link: LICENSE.md
library_name: executorch
tags:
- swipe-typing
- gesture-typing
- shape-writing
- keyboard
- on-device
- executorch
- ctc
- mobile
datasets:
- futo-org/swipe.futo.org
- futo-org/swipe-negatives
---

# FUTO Swipe

Mobile-oriented models for decoding swipe gestures into text.

<p align="center">
  <img src="https://huggingface.co/futo-org/futo-swipe/resolve/main/animations/swipe_demo_computer.gif" width="640" alt="Swipe decode of the word 'computer'">
</p>

See the paper [(*coming soon*)](https://huggingface.co/futo-org/futo-swipe)
for more details.

## Models

This repository contains 3 CNN models that compose together.
Only the encoder is required. The decoder and language model are
additional refinements, leveraging specific layout and language information.
The encoder can decode for **any** keyboard layout,
while the decoder is English/QWERTY-only and the language model is English-only.

| Model | Codename | Role | Params | Size (fp32) |
|-------|----------|------|-------:|------------:|
| **Encoder** | `honorable_sturgeon` | Maps a swipe trajectory to per-timestep character emissions. Layout-agnostic — works on any keyboard supplied at runtime. | 635 K | 2.65 MB |
| **Decoder** | `magic_macaw` | (Optional) per-layout refinement over the encoder's frozen features. Lifts top-k where layout-specific training data exists (English here). | 304 K | 1.25 MB |
| **Context LM** | `hungry_jellyfish` | (Optional) next-word and beam-rerank language model that blends sentence context into candidate ranking. | 1.5M | 6.25 MB |

### Encoder — `honorable_sturgeon`

A 1D temporal convolutional network (TCN) reads the raw `(x, y)` touch trajectory
and emits a 64-coefficient spectral pattern and a scalar
"intention" gate for each timestep. Per-key character scores are read off by evaluating a
fixed cosine (DCT) basis at the layout key centers. Switching layouts on
device requires no retraining, just a different key-coordinate tensor.

### Decoder (English/QWERTY) — `magic_macaw`

A small DFSMN decoder over the frozen encoder features. It refines the
character distribution on specific layouts.
Currently we only have data for training an English/QWERTY decoder.

### Context LM (English) — `hungry_jellyfish`

A causal DFSMN language model with a hash embedding for large vocabularies.
It can supply a rerank score and perform next-word prediction. This model
can be used with or without a decoder.

## Getting started

The example below demonstrates the encoder on CPU (x86) with the ExecuTorch
runtime and greedy-decodes a swipe into characters. Note that greedy decoding
is fairly inaccurate and should generally be improved by constraining to a
lexicon (eg. trie, WFST).

```python
import numpy as np
import torch
from huggingface_hub import hf_hub_download
from executorch.runtime import Runtime

# QWERTY letter centers in the normalized [0,1] keyboard frame. This is the
# only layout-specific input (can swap in another layout's key coordinates to
# decode a keyboard the encoder never saw at training time).
QWERTY = {
    "a": (0.10, 0.500), "b": (0.60, 0.833), "c": (0.40, 0.833), "d": (0.30, 0.500),
    "e": (0.25, 0.167), "f": (0.40, 0.500), "g": (0.50, 0.500), "h": (0.60, 0.500),
    "i": (0.75, 0.167), "j": (0.70, 0.500), "k": (0.80, 0.500), "l": (0.90, 0.500),
    "m": (0.80, 0.833), "n": (0.70, 0.833), "o": (0.85, 0.167), "p": (0.95, 0.167),
    "q": (0.05, 0.167), "r": (0.35, 0.167), "s": (0.20, 0.500), "t": (0.45, 0.167),
    "u": (0.65, 0.167), "v": (0.50, 0.833), "w": (0.15, 0.167), "x": (0.30, 0.833),
    "y": (0.55, 0.167), "z": (0.20, 0.833),
}
LETTERS = sorted(QWERTY)
MAX_KEYS = 64  # export-time padding bound

# A real swipe for the word "computer": normalized x, y and timestamps (ms).
PX = [0.4141, 0.4478, 0.5, 0.5741, 0.6599, 0.7256, 0.7744, 0.8098, 0.8485, 0.867,
      0.8737, 0.8653, 0.8418, 0.8182, 0.8098, 0.7963, 0.7946, 0.8081, 0.8418, 0.8704,
      0.9057, 0.9259, 0.9545, 0.9697, 0.968, 0.9529, 0.9141, 0.8468, 0.7811, 0.7273,
      0.6869, 0.6616, 0.6582, 0.6431, 0.6061, 0.5572, 0.5067, 0.4663, 0.4495, 0.4461,
      0.4411, 0.4192, 0.3872, 0.362, 0.3283, 0.2795, 0.2391, 0.2323, 0.2407, 0.2593,
      0.2879, 0.3249, 0.3468, 0.3569]
PY = [0.8991, 0.858, 0.7876, 0.6702, 0.5352, 0.4237, 0.3357, 0.2653, 0.1655, 0.142,
      0.142, 0.2183, 0.3709, 0.588, 0.7347, 0.8462, 0.8697, 0.811, 0.6115, 0.4707,
      0.3122, 0.2066, 0.1303, 0.1068, 0.1068, 0.1068, 0.1185, 0.1596, 0.1772, 0.1772,
      0.1772, 0.189, 0.189, 0.189, 0.1831, 0.189, 0.189, 0.189, 0.189, 0.189,
      0.1831, 0.1831, 0.1831, 0.1831, 0.1831, 0.1948, 0.189, 0.1948, 0.189, 0.189,
      0.189, 0.1831, 0.1831, 0.1831]
PT = [0.0, 100, 149, 197, 246, 297, 348, 399, 449, 498, 548, 598, 648, 698, 749, 799,
      849, 949, 999, 1047, 1100, 1152, 1197, 1248, 1314, 1364, 1414, 1465, 1515, 1565,
      1614, 1666, 1715, 1851, 1898, 1951, 1998, 2049, 2097, 2165, 2231, 2279, 2331,
      2382, 2431, 2481, 2532, 2584, 2649, 2700, 2751, 2798, 2848, 2899]


def resample(px, py, pt, T=64):
    """Resample a variable-length trajectory to T evenly-spaced points -> [2, T]."""
    x, y, t = map(np.asarray, (px, py, pt))
    t = t - t[0]
    if t[-1] > 1e-3:  # uniform 60 Hz resample, then to T points
        n60 = max(2, round(t[-1] / (1000.0 / 60.0)) + 1)
        tt = np.linspace(0.0, t[-1], n60)
        x, y = np.interp(tt, t, x), np.interp(tt, t, y)
    idx = np.linspace(0, len(x) - 1, T)
    rx = np.interp(idx, np.arange(len(x)), x)
    ry = np.interp(idx, np.arange(len(y)), y)
    return np.stack([rx, ry], axis=0).astype(np.float32)


def greedy_ctc(log_emissions):
    """Collapse the per-timestep argmax into a string (blank is the last class)."""
    blank = log_emissions.shape[-1] - 1
    out, prev = [], -1
    for c in log_emissions[0].argmax(axis=-1):
        if c != prev and c != blank and c < len(LETTERS):
            out.append(LETTERS[c])
        prev = c
    return "".join(out)


# Load the encoder .pte and run one forward pass.
pte = hf_hub_download("futo-org/futo-swipe", "honorable_sturgeon/model_fp32.pte")
encoder = Runtime.get().load_program(pte).load_method("forward")

features = torch.from_numpy(resample(PX, PY, PT)[None])     # [1, 2, 64]
keys = torch.zeros(1, MAX_KEYS, 2)                          # [1, 64, 2]
mask = torch.zeros(1, MAX_KEYS, dtype=torch.bool)           # [1, 64]
for i, ch in enumerate(LETTERS):
    keys[0, i] = torch.tensor(QWERTY[ch])
    mask[0, i] = True

log_emissions, coefficients, lambda_ = encoder.execute((features, keys, mask))
print("greedy decode:", greedy_ctc(log_emissions.numpy()))  # -> "computer"
```

Example output:

```
greedy decode: computer
```

### Encoder inputs and outputs

| | Tensor | Shape | Meaning |
|---|--------|-------|---------|
| **input** | `features` | `[1, 2, 64]` | Swipe trajectory `(x, y)` resampled to 64 points |
| **input** | `layout_keys` | `[1, 64, 2]` | Per-key `(x, y)` centers, padded to 64 keys |
| **input** | `layout_mask` | `[1, 64]` | Boolean mask of valid keys |
| **output** | `log_emissions` | `[1, 32, 65]` | Log-probabilities over 64 keys + blank |
| **output** | `coefficients` | `[1, 32, 64]` | Spectral coefficients (decoder features) |
| **output** | `lambda` | `[1, 32, 1]` | *Intention* gate (decoder features) |

The output time dimension is 32, half the 64 input points. The encoder
applies a 2× temporal downsample (a stride-2 adapter) inside the network, so
the 64 trajectory steps become 32 emission steps.

## License

Released under the [FUTO Model Weights License 1.0](LICENSE.md).