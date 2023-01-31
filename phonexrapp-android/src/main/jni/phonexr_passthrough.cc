//
// Created by domwet on 19.01.23.
//

#include "phonexr_passthrough.h"
#include <cmath>
#include <GLES2/gl2ext.h>
#include <GLES/gl.h>

namespace ndk_phonexr {

    static GLuint positionH = 0;
    static GLuint texCoordsH = 0;
    static GLuint samplerH = 0;
    static GLuint mvpTransH = 0;

    namespace {

// The objects are about 1 meter in radius, so the min/max target distance are
// set so that the objects are always within the room (which is about 5 meters
// across) and the reticle is always closer than any objects.
        constexpr float kMinTargetDistance = 2.5f;
        constexpr float kMaxTargetDistance = 3.5f;
        constexpr float kMinTargetHeight = 0.5f;
        constexpr float kMaxTargetHeight = kMinTargetHeight + 3.0f;

        constexpr float kDefaultFloorHeight = -1.7f;

        constexpr uint64_t kPredictionTimeWithoutVsyncNanos = 50000000;

// Angle threshold for determining whether the controller is pointing at the
// object.
        constexpr float kAngleLimit = 0.2f;

// Number of different possible targets
        constexpr int kTargetMeshCount = 3;

        constexpr char VERTEX_SHADER_SRC[] = R"SRC(
      attribute vec4 position;
      attribute vec4 texCoords;
      varying vec2 fragCoord;
      void main() {
        fragCoord = vec2(1.0 - texCoords.s, texCoords.t);
        gl_Position = position;
      }
)SRC";
            constexpr char FRAGMENT_SHADER_SRC[] = R"SRC(
      #extension GL_OES_EGL_image_external : require
      precision mediump float;
      uniform samplerExternalOES sampler;
      varying vec2 fragCoord;
      void main() {
        gl_FragColor = texture2D(sampler, fragCoord);
      }
)SRC";

    }  // anonymous namespace

    PhoneXRPassthrough::PhoneXRPassthrough(JavaVM *vm, jobject obj)
            : head_tracker_(nullptr),
              lens_distortion_(nullptr),
              distortion_renderer_(nullptr),
              screen_params_changed_(false),
              device_params_changed_(false),
              screen_width_(0),
              screen_height_(0),
              depthRenderBuffer_(0),
              framebuffer_(0),
              texture_(0),
              cam_texture_(0){
        vm->GetEnv((void **) &env, JNI_VERSION_1_6);
        Cardboard_initializeAndroid(vm, obj);
        head_tracker_ = CardboardHeadTracker_create();
        program_ = createProgram(VERTEX_SHADER_SRC, FRAGMENT_SHADER_SRC);
        positionH = glGetAttribLocation(program_, "position");
        texCoordsH = glGetAttribLocation(program_, "texCoords");
        samplerH = glGetUniformLocation(program_, "sampler");
        //mvpTransH = glGetUniformLocation(program_, "mvpTransform");
    }

    PhoneXRPassthrough::~PhoneXRPassthrough() {
        CardboardHeadTracker_destroy(head_tracker_);
        CardboardLensDistortion_destroy(lens_distortion_);
        CardboardDistortionRenderer_destroy(distortion_renderer_);
    }

    void PhoneXRPassthrough::setTextureId(GLuint texture) {
        this->cam_texture_ = texture;
    }

    void PhoneXRPassthrough::SetScreenParams(int width, int height) {
        screen_width_ = width;
        screen_height_ = height;
        screen_params_changed_ = true;
    }

    void PhoneXRPassthrough::OnDrawFrame(JNIEnv* env, jobject obj, jmethodID method) {
        if (!UpdateDeviceParams()) {
            return;
        }

        // Update Head Pose.
        head_view_ = GetPose();

        // Incorporate the floor height into the head_view
        head_view_ =
                head_view_ * GetTranslationMatrix({0.0f, kDefaultFloorHeight, 0.0f});

        // Bind buffer
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Draw eyes views
        for (int eye = 0; eye < 2; ++eye) {
            glViewport(eye == kLeft ? 0 : screen_width_ / 2, 0, screen_width_ / 2,
                       screen_height_);

            Matrix4x4 eye_matrix = GetMatrixFromGlArray(eye_matrices_[eye]);
            Matrix4x4 eye_view = eye_matrix * head_view_;

            Matrix4x4 projection_matrix =
                    GetMatrixFromGlArray(projection_matrices_[eye]);

            /*jfloatArray result = env->NewFloatArray(16);
            jfloat* pose = projection_matrix.ToGlArray().data();
            env->SetFloatArrayRegion(result,0,16,pose);
            env->CallVoidMethod(obj, method, result);*/

            drawCameraTexture();
        }

        // Render
        CardboardDistortionRenderer_renderEyeToDisplay(
                distortion_renderer_, 0, 0, 0,
                screen_width_, screen_height_, &left_eye_texture_description_,
                &right_eye_texture_description_);

        CHECKGLERROR("onDrawFrame");
    }

    void PhoneXRPassthrough::drawCameraTexture() {
        constexpr GLfloat vertices[] = {
                -1.0f, 1.0f,0.0f,
                1.0f,1.0f, 0.0f,
                -1.0f,-1.0f, 0.0f,
                1.0f,-1.0f, 0.0f
        };
        constexpr GLfloat texCoords[] = {
                0.0f, 0.0f, // Lower-left
                1.0f, 0.0f, // Lower-right
                0.0f, 1.0f, // Upper-left (order must match the vertices)
                1.0f, 1.0f  // Upper-right
        };


        GLint vertexComponents = 2;
        GLenum vertexType = GL_FLOAT;
        GLboolean normalized = GL_FALSE;
        GLsizei vertexStride = 0;

        glVertexAttribPointer(positionH,
                              vertexComponents, vertexType, normalized,
                              vertexStride, vertices);
        glEnableVertexAttribArray(positionH);
        glVertexAttribPointer(texCoordsH, vertexComponents, vertexType, normalized,
                              vertexStride, texCoords);
        glEnableVertexAttribArray(texCoordsH);


        //glUniformMatrix4fv(mvpTransH, 1,
        //                   GL_FALSE, mvpTransformArray);
        glUniform1f(samplerH, 0);

        glUseProgram(program_);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, cam_texture_);
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);

    }

    void PhoneXRPassthrough::OnPause() { CardboardHeadTracker_pause(head_tracker_); }

    void PhoneXRPassthrough::OnResume() {
        CardboardHeadTracker_resume(head_tracker_);

        // Parameters may have changed.
        device_params_changed_ = true;

        // Check for device parameters existence in external storage. If they're
        // missing, we must scan a Cardboard QR code and save the obtained parameters.
        uint8_t* buffer;
        int size;
        CardboardQrCode_getSavedDeviceParams(&buffer, &size);
        if (size == 0) {
            SwitchViewer();
        }
        CardboardQrCode_destroy(buffer);
    }

    void PhoneXRPassthrough::SwitchViewer() {
        CardboardQrCode_scanQrCodeAndSaveDeviceParams();
    }

    bool PhoneXRPassthrough::UpdateDeviceParams() {
        // Checks if screen or device parameters changed
        if (!screen_params_changed_ && !device_params_changed_) {
            return true;
        }

        // Get saved device parameters
        uint8_t* buffer;
        int size;
        CardboardQrCode_getSavedDeviceParams(&buffer, &size);

        // If there are no parameters saved yet, returns false.
        if (size == 0) {
            return false;
        }

        CardboardLensDistortion_destroy(lens_distortion_);
        lens_distortion_ = CardboardLensDistortion_create(buffer, size, screen_width_,
                                                          screen_height_);

        CardboardQrCode_destroy(buffer);

        GlSetup();

        CardboardDistortionRenderer_destroy(distortion_renderer_);
        distortion_renderer_ = CardboardOpenGlEs2DistortionRenderer_create();

        CardboardMesh left_mesh;
        CardboardMesh right_mesh;
        CardboardLensDistortion_getDistortionMesh(lens_distortion_, kLeft,
                                                  &left_mesh);
        CardboardLensDistortion_getDistortionMesh(lens_distortion_, kRight,
                                                  &right_mesh);

        CardboardDistortionRenderer_setMesh(distortion_renderer_, &left_mesh, kLeft);
        CardboardDistortionRenderer_setMesh(distortion_renderer_, &right_mesh,
                                            kRight);

        // Get eye matrices
        CardboardLensDistortion_getEyeFromHeadMatrix(lens_distortion_, kLeft,
                                                     eye_matrices_[0]);
        CardboardLensDistortion_getEyeFromHeadMatrix(lens_distortion_, kRight,
                                                     eye_matrices_[1]);
        CardboardLensDistortion_getProjectionMatrix(lens_distortion_, kLeft, kZNear,
                                                    kZFar, projection_matrices_[0]);
        CardboardLensDistortion_getProjectionMatrix(lens_distortion_, kRight, kZNear,
                                                    kZFar, projection_matrices_[1]);

        screen_params_changed_ = false;
        device_params_changed_ = false;

        CHECKGLERROR("UpdateDeviceParams");

        return true;
    }

    void PhoneXRPassthrough::GlSetup() {
        //LOGD("GL SETUP");

        if (framebuffer_ != 0) {
            GlTeardown();
        }

        // Create render texture.
        glGenTextures(1, &texture_);
        glBindTexture(GL_TEXTURE_2D, texture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, screen_width_, screen_height_, 0,
                     GL_RGB, GL_UNSIGNED_BYTE, 0);

        left_eye_texture_description_.texture = texture_;
        left_eye_texture_description_.left_u = 0;
        left_eye_texture_description_.right_u = 0.5;
        left_eye_texture_description_.top_v = 1;
        left_eye_texture_description_.bottom_v = 0;

        right_eye_texture_description_.texture = texture_;
        right_eye_texture_description_.left_u = 0.5;
        right_eye_texture_description_.right_u = 1;
        right_eye_texture_description_.top_v = 1;
        right_eye_texture_description_.bottom_v = 0;

        // Generate depth buffer to perform depth test.
        glGenRenderbuffers(1, &depthRenderBuffer_);
        glBindRenderbuffer(GL_RENDERBUFFER, depthRenderBuffer_);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, screen_width_,
                              screen_height_);
        CHECKGLERROR("Create Render buffer");

        // Create render target.
        glGenFramebuffers(1, &framebuffer_);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               texture_, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                                  GL_RENDERBUFFER, depthRenderBuffer_);

        CHECKGLERROR("GlSetup");
    }

    void PhoneXRPassthrough::GlTeardown() {
        if (framebuffer_ == 0) {
            return;
        }
        glDeleteRenderbuffers(1, &depthRenderBuffer_);
        depthRenderBuffer_ = 0;
        glDeleteFramebuffers(1, &framebuffer_);
        framebuffer_ = 0;
        glDeleteTextures(1, &texture_);
        texture_ = 0;

        CHECKGLERROR("GlTeardown");
    }

    Matrix4x4 PhoneXRPassthrough::GetPose() {
        std::array<float, 4> out_orientation;
        std::array<float, 3> out_position;
        CardboardHeadTracker_getPose(
                head_tracker_, GetBootTimeNano() + kPredictionTimeWithoutVsyncNanos,
                kLandscapeLeft, &out_position[0], &out_orientation[0]);
        return GetTranslationMatrix(out_position) *
               Quatf::FromXYZW(&out_orientation[0]).ToMatrix();
    }

}