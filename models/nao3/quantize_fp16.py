"""Re-export the NAO3 FP16 quality row from the FP32 reference."""

from pathlib import Path

from ai_edge_quantizer import quantizer, recipe_manager
from ai_edge_quantizer.recipe import AlgorithmName, qtyping


ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "converted" / "ecg_nao3_student_fp32.tflite"
OUTPUT = ROOT / "converted" / "ecg_nao3_student_fp16.tflite"


def main() -> None:
    manager = recipe_manager.RecipeManager()
    manager.add_quantization_config(
        regex=".*",
        operation_name=qtyping.TFLOperationName.ALL_SUPPORTED,
        op_config=qtyping.OpQuantizationConfig(
            weight_tensor_config=qtyping.TensorQuantizationConfig(
                num_bits=16,
                dtype=qtyping.TensorDataType.FLOAT,
            ),
            compute_precision=qtyping.ComputePrecision.FLOAT,
        ),
        algorithm_key=AlgorithmName.FLOAT_CASTING,
    )
    quantizer.Quantizer(str(SOURCE), manager.get_quantization_recipe()).quantize().export_model(
        str(OUTPUT)
    )


if __name__ == "__main__":
    main()
