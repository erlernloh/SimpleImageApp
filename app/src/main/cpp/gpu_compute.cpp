/**
 * gpu_compute.cpp - GPU Compute Shader Infrastructure Implementation
 */

#include "gpu_compute.h"
#include <fstream>
#include <sstream>

namespace ultradetail {

// ============================================================================
// GPUComputeContext Implementation
// ============================================================================

GPUComputeContext::GPUComputeContext()
    : initialized_(false)
    , display_(EGL_NO_DISPLAY)
    , context_(EGL_NO_CONTEXT)
    , surface_(EGL_NO_SURFACE) {
}

GPUComputeContext::~GPUComputeContext() {
    release();
}

bool GPUComputeContext::initialize() {
    if (initialized_) return true;
    
    // Get default display
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("GPUComputeContext: Failed to get EGL display");
        return false;
    }
    
    // Initialize EGL
    EGLint major, minor;
    if (!eglInitialize(display_, &major, &minor)) {
        LOGE("GPUComputeContext: Failed to initialize EGL");
        return false;
    }
    
    LOGD("GPUComputeContext: EGL version %d.%d", major, minor);
    
    // Choose config for OpenGL ES 3.2
    EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    
    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(display_, configAttribs, &config, 1, &numConfigs) || numConfigs == 0) {
        LOGE("GPUComputeContext: Failed to choose EGL config");
        release();
        return false;
    }
    
    // Create pbuffer surface (for compute, we don't need a real surface)
    EGLint surfaceAttribs[] = {
        EGL_WIDTH, 1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };
    
    surface_ = eglCreatePbufferSurface(display_, config, surfaceAttribs);
    if (surface_ == EGL_NO_SURFACE) {
        LOGE("GPUComputeContext: Failed to create pbuffer surface");
        release();
        return false;
    }
    
    // Create OpenGL ES 3.2 context
    EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_CONTEXT_MINOR_VERSION, 2,
        EGL_NONE
    };
    
    context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, contextAttribs);
    if (context_ == EGL_NO_CONTEXT) {
        LOGW("GPUComputeContext: Failed to create GLES 3.2 context, trying 3.1");
        
        // Fallback to 3.1
        EGLint contextAttribs31[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_CONTEXT_MINOR_VERSION, 1,
            EGL_NONE
        };
        context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, contextAttribs31);
        
        if (context_ == EGL_NO_CONTEXT) {
            LOGE("GPUComputeContext: Failed to create OpenGL ES context");
            release();
            return false;
        }
    }
    
    // Make context current
    if (!makeCurrent()) {
        LOGE("GPUComputeContext: Failed to make context current");
        release();
        return false;
    }
    
    // Check for compute shader support
    const char* extensions = (const char*)glGetString(GL_EXTENSIONS);
    if (!extensions || strstr(extensions, "GL_ES_VERSION_3_2") == nullptr) {
        LOGW("GPUComputeContext: OpenGL ES 3.2 compute shaders not supported");
        release();
        return false;
    }
    
    initialized_ = true;
    LOGD("GPUComputeContext: Initialized successfully");
    return true;
}

bool GPUComputeContext::makeCurrent() {
    if (!initialized_ || display_ == EGL_NO_DISPLAY) return false;
    return eglMakeCurrent(display_, surface_, surface_, context_) == EGL_TRUE;
}

void GPUComputeContext::release() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        
        if (context_ != EGL_NO_CONTEXT) {
            eglDestroyContext(display_, context_);
            context_ = EGL_NO_CONTEXT;
        }
        
        if (surface_ != EGL_NO_SURFACE) {
            eglDestroySurface(display_, surface_);
            surface_ = EGL_NO_SURFACE;
        }
        
        eglTerminate(display_);
        display_ = EGL_NO_DISPLAY;
    }
    
    initialized_ = false;
}

// ============================================================================
// ComputeShader Implementation
// ============================================================================

ComputeShader::ComputeShader()
    : program_(0)
    , shader_(0) {
}

ComputeShader::~ComputeShader() {
    if (program_ != 0) {
        glDeleteProgram(program_);
    }
}

bool ComputeShader::loadFromSource(const char* source) {
    return compileShader(source);
}

bool ComputeShader::loadFromFile(const char* filepath) {
    std::ifstream file(filepath);
    if (!file.is_open()) {
        LOGE("ComputeShader: Failed to open file: %s", filepath);
        return false;
    }
    
    std::stringstream buffer;
    buffer << file.rdbuf();
    std::string source = buffer.str();
    
    return compileShader(source.c_str());
}

bool ComputeShader::compileShader(const char* source) {
    // Create compute shader
    shader_ = glCreateShader(GL_COMPUTE_SHADER);
    if (shader_ == 0) {
        LOGE("ComputeShader: Failed to create compute shader");
        return false;
    }
    
    // Compile shader
    glShaderSource(shader_, 1, &source, nullptr);
    glCompileShader(shader_);
    
    if (!checkCompileErrors(shader_)) {
        glDeleteShader(shader_);
        shader_ = 0;
        return false;
    }
    
    // Create program
    program_ = glCreateProgram();
    if (program_ == 0) {
        LOGE("ComputeShader: Failed to create program");
        glDeleteShader(shader_);
        shader_ = 0;
        return false;
    }
    
    // Link program
    glAttachShader(program_, shader_);
    glLinkProgram(program_);
    
    if (!checkLinkErrors(program_)) {
        glDeleteProgram(program_);
        glDeleteShader(shader_);
        program_ = 0;
        shader_ = 0;
        return false;
    }
    
    // Shader can be deleted after linking
    glDeleteShader(shader_);
    shader_ = 0;
    
    LOGD("ComputeShader: Compiled and linked successfully");
    return true;
}

bool ComputeShader::checkCompileErrors(GLuint shader) {
    GLint success;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &success);
    
    if (!success) {
        GLchar infoLog[1024];
        glGetShaderInfoLog(shader, 1024, nullptr, infoLog);
        LOGE("ComputeShader: Compilation failed:\n%s", infoLog);
        return false;
    }
    
    return true;
}

bool ComputeShader::checkLinkErrors(GLuint program) {
    GLint success;
    glGetProgramiv(program, GL_LINK_STATUS, &success);
    
    if (!success) {
        GLchar infoLog[1024];
        glGetProgramInfoLog(program, 1024, nullptr, infoLog);
        LOGE("ComputeShader: Linking failed:\n%s", infoLog);
        return false;
    }
    
    return true;
}

void ComputeShader::use() {
    if (program_ != 0) {
        glUseProgram(program_);
    }
}

void ComputeShader::dispatch(int numGroupsX, int numGroupsY, int numGroupsZ) {
    if (program_ == 0) return;
    
    glDispatchCompute(numGroupsX, numGroupsY, numGroupsZ);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
}

void ComputeShader::setUniform1i(const char* name, int value) {
    GLint location = glGetUniformLocation(program_, name);
    if (location != -1) {
        glUniform1i(location, value);
    }
}

void ComputeShader::setUniform1f(const char* name, float value) {
    GLint location = glGetUniformLocation(program_, name);
    if (location != -1) {
        glUniform1f(location, value);
    }
}

void ComputeShader::setUniform2i(const char* name, int x, int y) {
    GLint location = glGetUniformLocation(program_, name);
    if (location != -1) {
        glUniform2i(location, x, y);
    }
}

void ComputeShader::setUniform2f(const char* name, float x, float y) {
    GLint location = glGetUniformLocation(program_, name);
    if (location != -1) {
        glUniform2f(location, x, y);
    }
}

// ============================================================================
// GPUTexture Implementation
// ============================================================================

GPUTexture::GPUTexture()
    : texture_(0)
    , width_(0)
    , height_(0)
    , format_(GL_RGBA8) {
}

GPUTexture::~GPUTexture() {
    if (texture_ != 0) {
        glDeleteTextures(1, &texture_);
    }
}

bool GPUTexture::createFromImage(const RGBImage& image) {
    if (image.width == 0 || image.height == 0) {
        LOGE("GPUTexture: Invalid image dimensions");
        return false;
    }
    
    // Convert RGBImage (float 0.0-1.0) to RGBA format (uint8 0-255)
    std::vector<uint8_t> data(image.width * image.height * 4);
    for (int y = 0; y < image.height; ++y) {
        for (int x = 0; x < image.width; ++x) {
            const RGBPixel& pixel = image.at(x, y);
            int idx = (y * image.width + x) * 4;
            data[idx + 0] = static_cast<uint8_t>(std::min(255.0f, std::max(0.0f, pixel.r * 255.0f)));
            data[idx + 1] = static_cast<uint8_t>(std::min(255.0f, std::max(0.0f, pixel.g * 255.0f)));
            data[idx + 2] = static_cast<uint8_t>(std::min(255.0f, std::max(0.0f, pixel.b * 255.0f)));
            data[idx + 3] = 255;
        }
    }
    
    // Create texture
    if (!create(image.width, image.height, GL_RGBA8)) {
        return false;
    }
    
    // Upload data
    glBindTexture(GL_TEXTURE_2D, texture_);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width_, height_, 
                    GL_RGBA, GL_UNSIGNED_BYTE, data.data());
    glBindTexture(GL_TEXTURE_2D, 0);
    
    return true;
}

bool GPUTexture::create(int width, int height, GLenum format) {
    if (texture_ != 0) {
        glDeleteTextures(1, &texture_);
    }
    
    width_ = width;
    height_ = height;
    format_ = format;
    
    glGenTextures(1, &texture_);
    glBindTexture(GL_TEXTURE_2D, texture_);
    
    glTexStorage2D(GL_TEXTURE_2D, 1, format_, width_, height_);
    
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    
    glBindTexture(GL_TEXTURE_2D, 0);
    
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        LOGE("GPUTexture: Failed to create texture (error: 0x%x)", error);
        glDeleteTextures(1, &texture_);
        texture_ = 0;
        return false;
    }
    
    return true;
}

RGBImage GPUTexture::readToImage() {
    RGBImage image;
    if (texture_ == 0) return image;
    
    image.resize(width_, height_);
    
    // Read texture data via FBO (OpenGL ES doesn't have glGetTexImage)
    std::vector<uint8_t> data(width_ * height_ * 4);
    
    // Create temporary FBO to read texture
    GLuint fbo;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture_, 0);
    
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
        glReadPixels(0, 0, width_, height_, GL_RGBA, GL_UNSIGNED_BYTE, data.data());
    } else {
        LOGE("GPUTexture: Failed to create FBO for texture readback");
    }
    
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    
    // Convert to RGBImage (uint8 0-255 -> float 0.0-1.0)
    for (int y = 0; y < height_; ++y) {
        for (int x = 0; x < width_; ++x) {
            int idx = (y * width_ + x) * 4;
            RGBPixel& pixel = image.at(x, y);
            pixel.r = data[idx + 0] / 255.0f;
            pixel.g = data[idx + 1] / 255.0f;
            pixel.b = data[idx + 2] / 255.0f;
        }
    }
    
    return image;
}

void GPUTexture::bind(int unit) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, texture_);
}

void GPUTexture::bindImage(int unit, GLenum access) {
    glBindImageTexture(unit, texture_, 0, GL_FALSE, 0, access, format_);
}

// ============================================================================
// UniformBuffer Implementation
// ============================================================================

UniformBuffer::UniformBuffer()
    : buffer_(0)
    , size_(0) {
}

UniformBuffer::~UniformBuffer() {
    if (buffer_ != 0) {
        glDeleteBuffers(1, &buffer_);
    }
}

bool UniformBuffer::create(size_t size) {
    if (buffer_ != 0) {
        glDeleteBuffers(1, &buffer_);
    }
    
    size_ = size;
    
    glGenBuffers(1, &buffer_);
    glBindBuffer(GL_UNIFORM_BUFFER, buffer_);
    glBufferData(GL_UNIFORM_BUFFER, size, nullptr, GL_DYNAMIC_DRAW);
    glBindBuffer(GL_UNIFORM_BUFFER, 0);
    
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        LOGE("UniformBuffer: Failed to create buffer (error: 0x%x)", error);
        glDeleteBuffers(1, &buffer_);
        buffer_ = 0;
        return false;
    }
    
    return true;
}

void UniformBuffer::update(const void* data, size_t size, size_t offset) {
    if (buffer_ == 0) return;
    
    glBindBuffer(GL_UNIFORM_BUFFER, buffer_);
    glBufferSubData(GL_UNIFORM_BUFFER, offset, size, data);
    glBindBuffer(GL_UNIFORM_BUFFER, 0);
}

void UniformBuffer::bind(int bindingPoint) {
    if (buffer_ == 0) return;
    glBindBufferBase(GL_UNIFORM_BUFFER, bindingPoint, buffer_);
}

// ============================================================================
// GPUTextureSynthesizer Implementation
// ============================================================================

GPUTextureSynthesizer::GPUTextureSynthesizer()
    : initialized_(false) {
}

GPUTextureSynthesizer::~GPUTextureSynthesizer() {
}

bool GPUTextureSynthesizer::initialize() {
    if (initialized_) return true;
    
    // Initialize GPU context
    if (!context_.initialize()) {
        LOGE("GPUTextureSynthesizer: Failed to initialize GPU context");
        return false;
    }
    
    // Load shader
    if (!loadShader()) {
        LOGE("GPUTextureSynthesizer: Failed to load shader");
        return false;
    }
    
    // Create uniform buffer
    if (!paramsBuffer_.create(sizeof(GPUSynthParams))) {
        LOGE("GPUTextureSynthesizer: Failed to create uniform buffer");
        return false;
    }
    
    initialized_ = true;
    LOGD("GPUTextureSynthesizer: Initialized successfully");
    return true;
}

bool GPUTextureSynthesizer::loadShader() {
    // Phase 2: Load from embedded source (simplified)
    // Phase 3: Load from assets
    
    // For now, return false to use CPU fallback
    // Actual shader loading will be implemented when asset loading is set up
    LOGD("GPUTextureSynthesizer: Shader loading not yet implemented");
    return false;
}

bool GPUTextureSynthesizer::processTile(
    const RGBImage& input,
    RGBImage& output,
    const GPUSynthParams& params
) {
    if (!initialized_) return false;
    
    // Phase 2 stub: GPU tile processing
    // Phase 3 will implement full GPU synthesis
    
    LOGD("GPUTextureSynthesizer: GPU tile processing not yet fully implemented");
    return false;
}

} // namespace ultradetail
