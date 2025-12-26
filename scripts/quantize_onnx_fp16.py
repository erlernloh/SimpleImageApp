#!/usr/bin/env python3
"""
Quantize ONNX model to FP16 for mobile deployment.

This reduces model size by ~50% with minimal quality loss.

Usage:
    python quantize_onnx_fp16.py -i realesrgan_x4plus.onnx

Output:
    - realesrgan_x4plus_fp16.onnx (~32MB)
"""

import os
import argparse

def quantize_to_fp16(input_path: str, output_path: str):
    """Convert ONNX model from FP32 to FP16."""
    import onnx
    from onnx import numpy_helper
    import numpy as np
    
    print(f"Loading model: {input_path}")
    model = onnx.load(input_path)
    
    print("Converting to FP16...")
    
    # Convert all float32 tensors to float16
    for tensor in model.graph.initializer:
        if tensor.data_type == onnx.TensorProto.FLOAT:
            # Convert float32 to float16
            float32_data = numpy_helper.to_array(tensor)
            float16_data = float32_data.astype(np.float16)
            
            # Update tensor
            tensor.ClearField('float_data')
            tensor.ClearField('raw_data')
            tensor.data_type = onnx.TensorProto.FLOAT16
            tensor.raw_data = float16_data.tobytes()
    
    # Update graph inputs/outputs to float16
    for input_tensor in model.graph.input:
        if input_tensor.type.tensor_type.elem_type == onnx.TensorProto.FLOAT:
            input_tensor.type.tensor_type.elem_type = onnx.TensorProto.FLOAT16
    
    for output_tensor in model.graph.output:
        if output_tensor.type.tensor_type.elem_type == onnx.TensorProto.FLOAT:
            output_tensor.type.tensor_type.elem_type = onnx.TensorProto.FLOAT16
    
    # Update nodes to use float16
    for node in model.graph.node:
        for attr in node.attribute:
            if attr.HasField('t') and attr.t.data_type == onnx.TensorProto.FLOAT:
                float32_data = numpy_helper.to_array(attr.t)
                float16_data = float32_data.astype(np.float16)
                attr.t.ClearField('float_data')
                attr.t.ClearField('raw_data')
                attr.t.data_type = onnx.TensorProto.FLOAT16
                attr.t.raw_data = float16_data.tobytes()
    
    print(f"Saving to: {output_path}")
    onnx.save(model, output_path)
    
    # Report sizes
    original_size = os.path.getsize(input_path) / (1024 * 1024)
    fp16_size = os.path.getsize(output_path) / (1024 * 1024)
    reduction = (1 - fp16_size / original_size) * 100
    
    print(f"\n✓ Quantization complete!")
    print(f"  Original (FP32): {original_size:.1f} MB")
    print(f"  Quantized (FP16): {fp16_size:.1f} MB")
    print(f"  Size reduction: {reduction:.1f}%")

def verify_fp16_model(onnx_path: str, tile_size: int = 256):
    """Verify the FP16 model works correctly."""
    import onnxruntime as ort
    import numpy as np
    
    print(f"\nVerifying FP16 model...")
    
    session = ort.InferenceSession(onnx_path)
    
    input_name = session.get_inputs()[0].name
    output_name = session.get_outputs()[0].name
    
    # Test inference
    dummy_input = np.random.randn(1, 3, tile_size, tile_size).astype(np.float32)
    output = session.run([output_name], {input_name: dummy_input})[0]
    
    expected_shape = (1, 3, tile_size * 4, tile_size * 4)
    assert output.shape == expected_shape, f"Shape mismatch: {output.shape} vs {expected_shape}"
    
    print(f"✓ FP16 model verification successful")
    print(f"  Input: {dummy_input.shape} -> Output: {output.shape}")

def main():
    parser = argparse.ArgumentParser(description='Quantize ONNX model to FP16')
    parser.add_argument('--input', '-i', default='realesrgan_x4plus.onnx',
                        help='Input ONNX model path (FP32)')
    parser.add_argument('--output', '-o', default=None,
                        help='Output ONNX model path (FP16). Default: adds _fp16 suffix')
    parser.add_argument('--tile-size', '-t', type=int, default=256,
                        help='Tile size for verification (default: 256)')
    parser.add_argument('--verify', '-v', action='store_true', default=True,
                        help='Verify the quantized model (default: True)')
    parser.add_argument('--no-verify', action='store_false', dest='verify',
                        help='Skip verification')
    
    args = parser.parse_args()
    
    if not os.path.exists(args.input):
        print(f"Error: Input model not found: {args.input}")
        return 1
    
    # Generate output path if not specified
    if args.output is None:
        base, ext = os.path.splitext(args.input)
        args.output = f"{base}_fp16{ext}"
    
    print("=" * 60)
    print("ONNX FP16 Quantization")
    print("=" * 60)
    
    # Quantize
    quantize_to_fp16(args.input, args.output)
    
    # Verify
    if args.verify:
        verify_fp16_model(args.output, args.tile_size)
    
    print("\n" + "=" * 60)
    print("Done!")
    print("=" * 60)
    print(f"\nCopy to Android assets:")
    print(f"  cp {args.output} app/src/main/assets/")
    
    return 0

if __name__ == '__main__':
    exit(main())
