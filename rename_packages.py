#!/usr/bin/env python3
"""
Script to rename all package references from com.imagedit.app to com.photara.app
"""

import os
import re

def replace_in_file(file_path, old_text, new_text):
    """Replace text in a file"""
    try:
        with open(file_path, 'r', encoding='utf-8') as file:
            content = file.read()
        
        if old_text in content:
            new_content = content.replace(old_text, new_text)
            with open(file_path, 'w', encoding='utf-8') as file:
                file.write(new_content)
            print(f"Updated: {file_path}")
            return True
        return False
    except Exception as e:
        print(f"Error processing {file_path}: {e}")
        return False

def find_and_replace_in_directory(directory, old_package, new_package):
    """Find all Kotlin files and replace package references"""
    updated_files = []
    
    for root, dirs, files in os.walk(directory):
        for file in files:
            if file.endswith('.kt'):
                file_path = os.path.join(root, file)
                if replace_in_file(file_path, old_package, new_package):
                    updated_files.append(file_path)
    
    return updated_files

if __name__ == "__main__":
    # Define the source directory
    src_dir = r"C:\Users\Er Lern\SimpleImageApp\app\src\main\java"
    
    # Define old and new package names
    old_package = "com.imagedit.app"
    new_package = "com.photara.app"
    
    print(f"Renaming packages from {old_package} to {new_package}")
    print(f"Searching in: {src_dir}")
    
    # Perform the replacement
    updated_files = find_and_replace_in_directory(src_dir, old_package, new_package)
    
    print(f"\nCompleted! Updated {len(updated_files)} files:")
    for file_path in updated_files:
        print(f"  - {file_path}")
