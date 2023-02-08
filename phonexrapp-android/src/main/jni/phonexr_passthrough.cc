/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "phonexr_passthrough.h"

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <array>
#include <cmath>
#include <fstream>
#include <GLES2/gl2ext.h>

#include "cardboard.h"

namespace ndk_phonexr {

    namespace {
        float size = 1;
        float x0 = -size, y0 =  size; // Top left
        float x1 =  size, y1 =  size; // Top right
        float x2 =  size, y2 = -size; // Bottom right
        float x3 = -size, y3 = -size; // Bottom left

        float plane_vert[] = {
                x3,y3,
                x2,y2,
                x0,y0,
                x1,y1,
        };

        float plane_tex_coords[] = {
                0.0,1.0,
                1.0,1.0,
                0.0,0.0,
                1.0,0.0
        };

        static GLuint testprogram;
        static const bool TEST = true;

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

// Simple shaders to render .obj files without any lighting.
        constexpr const char* kObjVertexShader =
                R"glsl(
    uniform mat4 u_MVP;
    attribute vec4 a_Position;
    attribute vec2 a_UV;
    varying vec2 v_UV;

    void main() {
      v_UV = a_UV;
      gl_Position = a_Position;
    })glsl";

        constexpr const char* kObjFragmentShader =
                R"glsl(
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 v_UV;
    uniform samplerExternalOES sTexture;
    void main() {
        gl_FragColor = texture2D(sTexture, v_UV);
    })glsl";

        constexpr const char* testFragment =
                R"glsl(
        precision mediump float;
        varying vec2 v_UV;
        void main() {
             gl_FragColor = vec4(1,0,0,1);
        }
        )glsl";

        struct render_viewport_ {
            int x, y, width, height;
        } renderViewport;

    }  // anonymous namespace

    PhoneXRPassthrough::PhoneXRPassthrough(JavaVM* vm, jobject obj)
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
              obj_program_(0),
              obj_position_param_(0),
              obj_uv_param_(0),
              obj_modelview_projection_param_(0) {
        JNIEnv* env;
        vm->GetEnv((void**)&env, JNI_VERSION_1_6);

        Cardboard_initializeAndroid(vm, obj);
        head_tracker_ = CardboardHeadTracker_create();
        createPassthroughPlane();
    }

    PhoneXRPassthrough::~PhoneXRPassthrough() {
        CardboardHeadTracker_destroy(head_tracker_);
        CardboardLensDistortion_destroy(lens_distortion_);
        CardboardDistortionRenderer_destroy(distortion_renderer_);
    }

    GLuint PhoneXRPassthrough::OnSurfaceCreated() {
        const int obj_vertex_shader =
                LoadGLShader(GL_VERTEX_SHADER, kObjVertexShader);
        const int obj_fragment_shader =
                LoadGLShader(GL_FRAGMENT_SHADER, kObjFragmentShader);
        const int test_fragment =
                LoadGLShader(GL_FRAGMENT_SHADER, testFragment);

        testprogram = glCreateProgram();
        glAttachShader(testprogram, obj_vertex_shader);
        glAttachShader(testprogram, test_fragment);
        glLinkProgram(testprogram);

        obj_program_ = glCreateProgram();
        glAttachShader(obj_program_, obj_vertex_shader);
        glAttachShader(obj_program_, obj_fragment_shader);
        glLinkProgram(obj_program_);

        glUseProgram(obj_program_);
        obj_position_param_ = glGetAttribLocation(obj_program_, "a_Position");
        obj_uv_param_ = glGetAttribLocation(obj_program_, "a_UV");
        obj_modelview_projection_param_ = glGetUniformLocation(obj_program_, "u_MVP");

        // TODO initialize plane mesh and texture
        glGenTextures(1, &cam_texture_);
        glActiveTexture(GL_TEXTURE0);

        glBindTexture(GL_TEXTURE_EXTERNAL_OES, cam_texture_);
        glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);

        CHECKGLERROR("OnSurfaceCreated");
        return cam_texture_;
    }

    void PhoneXRPassthrough::SetScreenParams(int width, int height) {
        renderViewport.x = 0;
        renderViewport.y = 0;
        renderViewport.width = width;
        renderViewport.height = height;

        screen_width_ = width;
        screen_height_ = height;
        screen_params_changed_ = true;
    }

    void PhoneXRPassthrough::OnDrawFrame() {
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
            glViewport(eye == kLeft ? 0 : screen_width_/2, 0, screen_width_/2,
                       screen_height_);

            Matrix4x4 eye_matrix = GetMatrixFromGlArray(eye_matrices_[eye]);
            Matrix4x4 eye_view = eye_matrix;// * head_view_;
            //Matrix4x4 eye_view = eye_matrix * head_view_;

            Matrix4x4 projection_matrix =
                    GetMatrixFromGlArray(projection_matrices_[eye]);
            modelview_projection_room_ = projection_matrix * eye_view;

            // Draw passthrough
            drawPassthroughPlane();
        }

        // Render
        // Position and size can be changed
        CardboardDistortionRenderer_renderEyeToDisplay(
                distortion_renderer_, 0, renderViewport.x, renderViewport.y,
                renderViewport.width, renderViewport.height, &left_eye_texture_description_,
                &right_eye_texture_description_);

        CHECKGLERROR("onDrawFrame");
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
        LOGD("GL SETUP");

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
        //glDeleteTextures(1, &cam_texture_);
        //cam_texture_ = 0;

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

    void PhoneXRPassthrough::createPassthroughPlane() {
        float size = passthrough_size;
        float x0 = -size, y0 =  size; // Top left
        float x1 =  size, y1 =  size; // Top right
        float x2 =  size, y2 = -size; // Bottom right
        float x3 = -size, y3 = -size; // Bottom left

        passthrough_vertices[0] = x3; passthrough_vertices[1] = y3;
        passthrough_vertices[2] = x2; passthrough_vertices[3] = y2;
        passthrough_vertices[4] = x0; passthrough_vertices[5] = y0;
        passthrough_vertices[6] = x1; passthrough_vertices[7] = y1;
    }

    void PhoneXRPassthrough::SetPassthroughSize(float size) {
        passthrough_size = size;
        createPassthroughPlane();
    }

    void PhoneXRPassthrough::SetRenderViewport(int x, int y, int width, int height) {
        renderViewport.x = x;
        renderViewport.y = y;
        renderViewport.width = width;
        renderViewport.height = height;
    }

     void PhoneXRPassthrough::drawPassthroughPlane() {

        glUseProgram(obj_program_);
        // Bind texture
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, cam_texture_);

        // Draw Mesh
        glEnableVertexAttribArray(obj_position_param_);
        glVertexAttribPointer(obj_position_param_, 2, GL_FLOAT, false, 0,
                              passthrough_vertices);
        glEnableVertexAttribArray(obj_uv_param_);
        glVertexAttribPointer(obj_uv_param_, 2, GL_FLOAT, false, 0, plane_tex_coords);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        CHECKGLERROR("DrawRoom");
    }

}  // namespace ndk_hello_cardboard
