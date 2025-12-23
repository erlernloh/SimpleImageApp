#!/usr/bin/env python3
"""
update_download_urls.py - Update model download URLs in ModelDownloader.kt

Usage:
    python update_download_urls.py --username YOUR_GITHUB_USERNAME --version v1.0.0
"""

import argparse
import re
from pathlib import Path

def update_urls(kotlin_file: Path, github_username: str, version: str):
    """Update download URLs in ModelDownloader.kt"""
    
    if not kotlin_file.exists():
        print(f"Error: {kotlin_file} not found")
        return False
    
    # Read file
    content = kotlin_file.read_text(encoding='utf-8')
    
    # Base URL pattern
    base_url = f"https://github.com/{github_username}/SimpleImageApp/releases/download/{version}"
    
    # Model URL mappings
    url_mappings = {
        'REAL_ESRGAN_X4_FP16': f"{base_url}/realesrgan_x4plus_fp16.onnx",
        'REAL_ESRGAN_X4_ANIME_FP16': f"{base_url}/realesrgan_x4plus_anime_fp16.onnx",
        'SWINIR_X4_FP16': f"{base_url}/swinir_x4_fp16.onnx",
    }
    
    # Update each model's downloadUrl
    for model_name, new_url in url_mappings.items():
        # Pattern to match the downloadUrl line for this model
        pattern = rf'(val {model_name} = ModelInfo\([^)]*downloadUrl = )"[^"]*"'
        replacement = rf'\1"{new_url}"'
        
        content, count = re.subn(pattern, replacement, content, flags=re.DOTALL)
        
        if count > 0:
            print(f"✓ Updated {model_name}")
        else:
            print(f"⚠ Could not find {model_name} (might be OK if not defined yet)")
    
    # Write back
    kotlin_file.write_text(content, encoding='utf-8')
    print(f"\n✓ Updated {kotlin_file}")
    return True

def main():
    parser = argparse.ArgumentParser(description='Update model download URLs')
    parser.add_argument('--username', '-u', required=True,
                        help='GitHub username (e.g., john-doe)')
    parser.add_argument('--version', '-v', default='v1.0.0',
                        help='Release version tag (default: v1.0.0)')
    parser.add_argument('--file', '-f', default=None,
                        help='Path to ModelDownloader.kt (auto-detected if not provided)')
    
    args = parser.parse_args()
    
    # Find ModelDownloader.kt
    if args.file:
        kotlin_file = Path(args.file)
    else:
        # Auto-detect from script location
        script_dir = Path(__file__).parent
        repo_root = script_dir.parent
        kotlin_file = repo_root / "app" / "src" / "main" / "java" / "com" / "imagedit" / "app" / "ultradetail" / "ModelDownloader.kt"
    
    print("=" * 60)
    print("Model Download URL Updater")
    print("=" * 60)
    print(f"GitHub username: {args.username}")
    print(f"Release version: {args.version}")
    print(f"Target file: {kotlin_file}")
    print("=" * 60)
    print()
    
    if update_urls(kotlin_file, args.username, args.version):
        print()
        print("=" * 60)
        print("Success!")
        print("=" * 60)
        print()
        print("Next steps:")
        print("1. Review changes in ModelDownloader.kt")
        print("2. Commit the updated URLs:")
        print("   git add app/src/main/java/com/imagedit/app/ultradetail/ModelDownloader.kt")
        print(f'   git commit -m "Update model download URLs for {args.version}"')
        print("3. Build and test the app")
        return 0
    else:
        return 1

if __name__ == '__main__':
    exit(main())
