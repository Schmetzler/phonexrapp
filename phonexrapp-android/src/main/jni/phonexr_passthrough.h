//
// Created by domwet on 19.01.23.
//

#ifndef PHONEXR_PHONEXR_PASSTHROUGH_H
#define PHONEXR_PHONEXR_PASSTHROUGH_H

#include <android/asset_manager.h>
#include <jni.h>

#include <memory>
#include <string>
#include <thread>
#include <vector>

#include <GLES2/gl2.h>
#include "cardboard.h"
#include "util.h"

namespace ndk_phonexr {

/**
 * Camera passthrough using Cardboard SDK.
 * It loads a screen, where the camera output is passed to.
 */
    class PhoneXRPassthrough {
    public:
        /**
         * Creates a HelloCardboardApp.
         *
         * @param vm JavaVM pointer.
         * @param obj Android activity object.
         */
        PhoneXRPassthrough(JavaVM* vm, jobject objs);

        ~PhoneXRPassthrough();

        /**
         * Sets screen parameters.
         *
         * @param width Screen width
         * @param height Screen height
         */
        void SetScreenParams(int width, int height);

        /**
         * Draws the scene. This should be called on the rendering thread.
         */
        void OnDrawFrame();

        /**
         * Pauses head tracking.
         */
        void OnPause();

        /**
         * Resumes head tracking.
         */
        void OnResume();

        /**
         * Allows user to switch viewer.
         */
        void SwitchViewer();

        /**
         * Gets head's pose as a 4x4 matrix.
         *
         * @return matrix containing head's pose.
         */
        Matrix4x4 GetPose();

        GLuint OnSurfaceCreated();

        void SetPassthroughSize(float size);

        private:
        /**
         * Default near clip plane z-axis coordinate.
         */
        static constexpr float kZNear = 0.1f;

        /**
         * Default far clip plane z-axis coordinate.
         */
        static constexpr float kZFar = 100.f;

        /**
         * Updates device parameters, if necessary.
         *
         * @return true if device parameters were successfully updated.
         */
        bool UpdateDeviceParams();

        void createPassthroughPlane();

        void drawPassthroughPlane();
        /**
         * Initializes GL environment.
         */
        void GlSetup();

        /**
         * Deletes GL environment.
         */
        void GlTeardown();

        CardboardHeadTracker* head_tracker_;
        CardboardLensDistortion* lens_distortion_;
        CardboardDistortionRenderer* distortion_renderer_;

        CardboardEyeTextureDescription left_eye_texture_description_;
        CardboardEyeTextureDescription right_eye_texture_description_;

        bool screen_params_changed_;
        bool device_params_changed_;
        int screen_width_;
        int screen_height_;

        float projection_matrices_[2][16];
        float eye_matrices_[2][16];

        GLuint depthRenderBuffer_;  // depth buffer
        GLuint framebuffer_;        // framebuffer object
        GLuint texture_;            // distortion texture

        GLuint obj_program_;
        GLuint obj_position_param_;
        GLuint obj_uv_param_;
        GLuint obj_modelview_projection_param_;

        Matrix4x4 modelview_projection_room_;

        GLuint cam_texture_{0};
        float passthrough_size = 0.5;
        float passthrough_vertices[8];

        Matrix4x4 head_view_;
    };

}  // namespace ndk_hello_cardboard

#endif //PHONEXR_PHONEXR_PASSTHROUGH_H
