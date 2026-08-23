"""Numerical host-parity gate for NAO3 FP16 and INT8 variants."""

from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter


ROOT = Path(__file__).resolve().parent / "converted"
MODEL_PATHS = {
    "fp32": ROOT / "ecg_nao3_student_fp32.tflite",
    "fp16": ROOT / "ecg_nao3_student_fp16.tflite",
    "int8": ROOT / "ecg_nao3_student_int8.tflite",
}


def load(path: Path):
    interpreter = Interpreter(model_path=str(path), num_threads=1)
    interpreter.allocate_tensors()
    return interpreter, interpreter.get_input_details()[0], interpreter.get_output_details()[0]


def invoke(runtime, values: np.ndarray) -> np.ndarray:
    interpreter, input_detail, output_detail = runtime
    if input_detail["dtype"] == np.float32:
        model_input = values
    else:
        scale, zero_point = input_detail["quantization"]
        model_input = np.clip(np.rint(values / scale + zero_point), -128, 127).astype(
            input_detail["dtype"]
        )
    interpreter.set_tensor(input_detail["index"], model_input)
    interpreter.invoke()
    output = interpreter.get_tensor(output_detail["index"]).astype(np.float32).reshape(-1)
    if output_detail["dtype"] != np.float32:
        scale, zero_point = output_detail["quantization"]
        output = (output - zero_point) * scale
    return output


def softmax(logits: np.ndarray) -> np.ndarray:
    shifted = logits - np.max(logits)
    exponents = np.exp(shifted)
    return exponents / exponents.sum()


def fixtures() -> list[np.ndarray]:
    rng = np.random.default_rng(20260823)
    time = np.arange(7680, dtype=np.float32) / 256.0
    cases = []
    for case in range(20):
        heart_hz = 0.8 + 0.07 * case
        signal = 0.05 * np.sin(2 * np.pi * heart_hz * time)
        signal += 0.02 * np.sin(2 * np.pi * (12 + case % 5) * time)
        signal += (0.005 + case * 0.0005) * rng.standard_normal(time.shape).astype(np.float32)
        period = max(120, int(256.0 / heart_hz))
        for center in range(50 + case % 31, 7680, period):
            indexes = np.arange(max(0, center - 8), min(7680, center + 9))
            signal[indexes] += (0.7 + 0.03 * (case % 4)) * np.exp(
                -0.5 * ((indexes - center) / 2.3) ** 2
            )
            t_indexes = np.arange(max(0, center + 35), min(7680, center + 80))
            signal[t_indexes] += 0.12 * np.exp(
                -0.5 * ((t_indexes - (center + 55)) / 12.0) ** 2
            )
        signal += (case % 3 - 1) * 0.05 * np.sin(2 * np.pi * 0.25 * time)
        signal = (signal - signal.mean()) / max(signal.std(), 1e-6)
        cases.append(signal.astype(np.float32).reshape(1, 7680, 1))
    return cases


def main() -> None:
    runtimes = {name: load(path) for name, path in MODEL_PATHS.items()}
    inputs = fixtures()
    outputs = {
        name: np.stack([invoke(runtime, values) for values in inputs])
        for name, runtime in runtimes.items()
    }
    reference = outputs["fp32"]
    reference_probabilities = np.stack([softmax(row) for row in reference])
    for name in ("fp16", "int8"):
        candidate = outputs[name]
        probabilities = np.stack([softmax(row) for row in candidate])
        correlation = np.corrcoef(reference.ravel(), candidate.ravel())[0, 1]
        max_probability_error = np.max(np.abs(reference_probabilities - probabilities))
        mean_probability_error = np.mean(np.abs(reference_probabilities - probabilities))
        agreement = np.mean(
            np.argmax(reference_probabilities, axis=1) == np.argmax(probabilities, axis=1)
        )
        print(
            f"{name}: logit_correlation={correlation:.8f} "
            f"max_probability_error={max_probability_error:.8f} "
            f"mean_probability_error={mean_probability_error:.8f} "
            f"argmax_agreement={agreement:.3f}"
        )
        if name == "fp16":
            assert correlation > 0.99999
            assert max_probability_error < 0.001
            assert agreement == 1.0


if __name__ == "__main__":
    main()
