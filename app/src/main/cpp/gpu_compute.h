/**
 * gpu_compute.h - GPU Compute Shader Infrastructure
 * 
 * Provides OpenGL ES 3.2 compute shader initialization and management
 * for texture synthesis GPU acceleration.
 */

#ifndef ULTRADETAIL_GPU_COMPUTE_H
#define ULTRADETAIL_GPU_COMPUTE_H

#include "common.h"
#include <GLES3/gl32.h>
#include <EGL/egl.h>
#include <string>
#include <vector>

namespace ultradetail {

/**
 * GPU compute context
 */
class GPUComputeContext {
public:
    GPUComputeContext();
    ~GPUComputeContext();
    
    /**
     * Initialize OpenGL ES 3.2 context for compute
     */
    bool initialize();
    
    /**
     * Check if context is valid
     */
    bool isValid() const { return initialized_; }
    
    /**
     * Make context current for this thread
     */
    bool makeCurrent();
    
    /**
     * Release context
     */
    void release();
    
private:
    bool initialized_;
    EGLDisplay display_;
    EGLContext context_;
    EGLSurface surface_;
};

/**
 * Compute shader program
 */
class ComputeShader {
public:
    ComputeShader();
    ~ComputeShader();
    
    /**
     * Load and compile compute shader from source
     */
    bool loadFromSource(const char* source);
    
    /**
     * Load compute shader from file
     */
    bool loadFromFile(const char* filepath);
    
    /**
     * Use this shader program
     */
    void use();
    
    /**
     * Dispatch compute shader
     */
    void dispatch(int numGroupsX, int numGroupsY, int numGroupsZ = 1);
    
    /**
     * Set uniform values
     */
    void setUniform1i(const char* name, int value);
    void setUniform1f(const char* name, float value);
    void setUniform2i(const char* name, int x, int y);
    void setUniform2f(const char* name, float x, float y);
    
    /**
     * Get program ID
     */
    GLuint getProgramId() const { return program_; }
    
    /**
     * Check if shader is valid
     */
    bool isValid() const { return program_ != 0; }
    
private:
    bool compileShader(const char* source);
    bool checkCompileErrors(GLuint shader);
    bool checkLinkErrors(GLuint program);
    
    GLuint program_;
    GLuint shader_;
};

/**
 * GPU texture wrapper
 */
class GPUTexture {
public:
    GPUTexture();
    ~GPUTexture();
    
    /**
     * Create texture from RGBImage
     */
    bool createFromImage(const RGBImage& image);
    
    /**
     * Create empty texture
     */
    bool create(int width, int height, GLenum format = GL_RGBA8);
    
    /**
     * Read texture data back to RGBImage
     */
    RGBImage readToImage();
    
    /**
     * Bind texture to unit
     */
    void bind(int unit);
    
    /**
     * Bind as image for compute shader
     */
    void bindImage(int unit, GLenum access = GL_READ_WRITE);
    
    /**
     * Get texture ID
     */
    GLuint getTextureId() const { return texture_; }
    
    /**
     * Get dimensions
     */
    int getWidth() const { return width_; }
    int getHeight() const { return height_; }
    
    /**
     * Check if valid
     */
    bool isValid() const { return texture_ != 0; }
    
private:
    GLuint texture_;
    int width_;
    int height_;
    GLenum format_;
};

/**
 * Uniform buffer object
 */
class UniformBuffer {
public:
    UniformBuffer();
    ~UniformBuffer();
    
    /**
     * Create buffer with size
     */
    bool create(size_t size);
    
    /**
     * Update buffer data
     */
    void update(const void* data, size_t size, size_t offset = 0);
    
    /**
     * Bind to binding point
     */
    void bind(int bindingPoint);
    
    /**
     * Get buffer ID
     */
    GLuint getBufferId() const { return buffer_; }
    
    /**
     * Check if valid
     */
    bool isValid() const { return buffer_ != 0; }
    
private:
    GLuint buffer_;
    size_t size_;
};

/**
 * Texture synthesis GPU parameters (matches shader uniform block)
 */
struct GPUSynthParams {
    int patchSize;
    int searchRadius;
    float blendWeight;
    float varianceThreshold;
    int tileOffsetX;
    int tileOffsetY;
    int tileWidth;
    int tileHeight;
    
    GPUSynthParams() 
        : patchSize(7)
        , searchRadius(20)
        , blendWeight(0.4f)
        , varianceThreshold(0.01f)
        , tileOffsetX(0)
        , tileOffsetY(0)
        , tileWidth(0)
        , tileHeight(0) {}
};

/**
 * GPU texture synthesis manager
 */
class GPUTextureSynthesizer {
public:
    GPUTextureSynthesizer();
    ~GPUTextureSynthesizer();
    
    /**
     * Initialize GPU resources
     */
    bool initialize();
    
    /**
     * Process tile on GPU
     */
    bool processTile(
        const RGBImage& input,
        RGBImage& output,
        const GPUSynthParams& params
    );
    
    /**
     * Check if initialized
     */
    bool isInitialized() const { return initialized_; }
    
private:
    bool loadShader();
    
    bool initialized_;
    GPUComputeContext context_;
    ComputeShader shader_;
    UniformBuffer paramsBuffer_;
};

} // namespace ultradetail

#endif // ULTRADETAIL_GPU_COMPUTE_H
