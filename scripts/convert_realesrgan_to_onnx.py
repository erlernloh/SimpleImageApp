#!/usr/bin/env python3
"""
Convert Real-ESRGAN PyTorch model to ONNX format for Android deployment.

Usage:
    1. Install dependencies:
       pip install torch torchvision onnx basicsr realesrgan

    2. Download the model:
       wget https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth

    3. Run this script:
       python convert_realesrgan_to_onnx.py

Output:
    - realesrgan_x4plus.onnx (FP32, ~64MB)
"""

import os
import torch
import torch.onnx

def load_realesrgan_model(model_path: str):
    """Load Real-ESRGAN model from PyTorch checkpoint."""
    from basicsr.archs.rrdbnet_arch import RRDBNet
    
    # Real-ESRGAN x4plus configuration
    model = RRDBNet(
        num_in_ch=3,
        num_out_ch=3,
        num_feat=64,
        num_block=23,
        num_grow_ch=32,
        scale=4
    )
    
    # Load weights
    checkpoint = torch.load(model_path, map_location='cpu')
    
    # Handle different checkpoint formats
    if 'params_ema' in checkpoint:
        model.load_state_dict(checkpoint['params_ema'], strict=True)
        print("Loaded params_ema weights")
    elif 'params' in checkpoint:
        model.load_state_dict(checkpoint['params'], strict=True)
        print("Loaded params weights")
    else:
        model.load_state_dict(checkpoint, strict=True)
        print("Loaded direct weights")
    
    model.eval()
    return model

def export_to_onnx(model, output_path: str, tile_size: int = 256):
    """Export PyTorch model to ONNX format."""
    
    # Create dummy input (NCHW format)
    dummy_input = torch.randn(1, 3, tile_size, tile_size)
    
    print(f"Exporting to ONNX with input shape: {dummy_input.shape}")
    print(f"Expected output shape: (1, 3, {tile_size * 4}, {tile_size * 4})")
    
    # Export with dynamic axes for flexible tile sizes
    torch.onnx.export(
        model,
        dummy_input,
        output_path,
        export_params=True,
        opset_version=14,  # Good compatibility with ONNX Runtime
        do_constant_folding=True,
        input_names=['input'],
        output_names=['output'],
        dynamic_axes={
            'input': {0: 'batch', 2: 'height', 3: 'width'},
            'output': {0: 'batch', 2: 'height', 3: 'width'}
        }
    )
    
    # Verify output
    file_size = os.path.getsize(output_path) / (1024 * 1024)
    print(f"✓ ONNX model exported: {output_path}")
    print(f"  File size: {file_size:.1f} MB")

def verify_onnx_model(onnx_path: str, tile_size: int = 256):
    """Verify the exported ONNX model."""
    import onnx
    import onnxruntime as ort
    import numpy as np
    
    # Check model validity
    model = onnx.load(onnx_path)
    onnx.checker.check_model(model)
    print("✓ ONNX model is valid")
    
    # Test inference
    session = ort.InferenceSession(onnx_path)
    
    input_name = session.get_inputs()[0].name
    output_name = session.get_outputs()[0].name
    
    print(f"  Input: {input_name}, shape: {session.get_inputs()[0].shape}")
    print(f"  Output: {output_name}, shape: {session.get_outputs()[0].shape}")
    
    # Run test inference
    dummy_input = np.random.randn(1, 3, tile_size, tile_size).astype(np.float32)
    output = session.run([output_name], {input_name: dummy_input})[0]
    
    expected_shape = (1, 3, tile_size * 4, tile_size * 4)
    assert output.shape == expected_shape, f"Shape mismatch: {output.shape} vs {expected_shape}"
    
    print(f"✓ Test inference successful")
    print(f"  Input: {dummy_input.shape} -> Output: {output.shape}")

def main():
    import argparse
    
    parser = argparse.ArgumentParser(description='Convert Real-ESRGAN to ONNX')
    parser.add_argument('--input', '-i', default='RealESRGAN_x4plus.pth',
                        help='Input PyTorch model path')
    parser.add_argument('--output', '-o', default='realesrgan_x4plus.onnx',
                        help='Output ONNX model path')
    parser.add_argument('--tile-size', '-t', type=int, default=256,
                        help='Tile size for export (default: 256)')
    parser.add_argument('--verify', '-v', action='store_true',
                        help='Verify the exported model')
    
    args = parser.parse_args()
    
    if not os.path.exists(args.input):
        print(f"Error: Model file not found: {args.input}")
        print("\nDownload the model with:")
        print("  wget https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth")
        return 1
    
    print("=" * 60)
    print("Real-ESRGAN to ONNX Converter")
    print("=" * 60)
    
    # Load model
    print(f"\nLoading model from: {args.input}")
    model = load_realesrgan_model(args.input)
    
    # Export to ONNX
    print(f"\nExporting to: {args.output}")
    export_to_onnx(model, args.output, args.tile_size)
    
    # Verify if requested
    if args.verify:
        print(f"\nVerifying model...")
        verify_onnx_model(args.output, args.tile_size)
    
    print("\n" + "=" * 60)
    print("Conversion complete!")
    print("=" * 60)
    print(f"\nNext step: Quantize to FP16 for mobile:")
    print(f"  python quantize_onnx_fp16.py -i {args.output}")
    
    return 0

if __name__ == '__main__':
    exit(main())
