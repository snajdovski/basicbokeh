package com.hadrosaur.basicbokeh

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import java.util.*


import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.AsyncTask
import android.os.Build
import android.view.TextureView
import com.hadrosaur.basicbokeh.CameraController.CameraStateCallback
import com.hadrosaur.basicbokeh.CameraController.FocusCaptureSessionCallback
import com.hadrosaur.basicbokeh.CameraController.FocusCaptureSessionCallback.Companion.STATE_PICTURE_TAKEN
import com.hadrosaur.basicbokeh.CameraController.FocusCaptureSessionCallback.Companion.STATE_WAITING_LOCK
import com.hadrosaur.basicbokeh.CameraController.FocusCaptureSessionCallback.Companion.STATE_WAITING_PRECAPTURE
import com.hadrosaur.basicbokeh.CameraController.PreviewSessionStateCallback
import com.hadrosaur.basicbokeh.CameraController.StillCaptureSessionCallback
import com.hadrosaur.basicbokeh.MainActivity.Companion.Logd
import com.hadrosaur.basicbokeh.MainActivity.Companion.cameraParams
import com.hadrosaur.basicbokeh.MainActivity.Companion.dualCamLogicalId
import com.hadrosaur.basicbokeh.MainActivity.Companion.normalLensId
import com.hadrosaur.basicbokeh.MainActivity.Companion.twoLens
import com.hadrosaur.basicbokeh.MainActivity.Companion.wideAngleId

fun createCameraPreviewSession(activity: MainActivity, camera: CameraDevice, params: CameraParams) {
    Logd("In createCameraPreviewSession.")
    if (!params.isOpen) {
//        camera2Abort(activity, params, testConfig)
        return
    }

    try {
        //If we have a dual camera, open both streams
        if (Build.VERSION.SDK_INT >= 28 && params.id.equals(MainActivity.dualCamLogicalId)) {
            val normalParams: CameraParams? = MainActivity.cameraParams.get(normalLensId)
            val wideParams: CameraParams? = MainActivity.cameraParams.get(wideAngleId)

            Logd("In createCameraPreview. This is a Dual Cam stream. Starting up simultaneous streams.")

            if (null == normalParams || null == wideParams)
                return

            val normalTexture = normalParams.previewTextureView?.surfaceTexture
            val wideTexture = wideParams.previewTextureView?.surfaceTexture

            if (null == normalTexture || null == wideTexture)
                return

            val normalSurface = Surface(normalTexture)
            val wideSurface = Surface(wideTexture)

            if (null == normalSurface || null == wideSurface)
                return

            val normalOutputConfigPreview = OutputConfiguration(normalSurface)
            val normalOutputConfigImageReader = OutputConfiguration(normalParams.imageReader?.surface!!)
            normalOutputConfigPreview.setPhysicalCameraId(normalLensId)
            normalOutputConfigImageReader.setPhysicalCameraId(normalLensId)

            val wideOutputConfigPreview = OutputConfiguration(wideSurface)
            val wideOutputConfigImageReader = OutputConfiguration(wideParams.imageReader?.surface!!)
            wideOutputConfigPreview.setPhysicalCameraId(wideAngleId)
            wideOutputConfigImageReader.setPhysicalCameraId(wideAngleId)

            val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    Arrays.asList(normalOutputConfigPreview, normalOutputConfigImageReader, wideOutputConfigPreview, wideOutputConfigImageReader),
                    params.backgroundExecutor, PreviewSessionStateCallback(activity, params))

            params.previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            params.previewBuilder?.addTarget(normalSurface)
            params.previewBuilder?.addTarget(wideSurface)

            camera.createCaptureSession(sessionConfig)

            //Else we do not have a dual cam situation, just worry about the single camera
        } else {
            val texture = params.previewTextureView?.surfaceTexture

            if (null == texture)
                return

            val surface = Surface(texture)

            if (null == surface)
                return

            params.previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            params.previewBuilder?.addTarget(surface)

            val imageSurface = params.imageReader?.surface
            if (null == imageSurface)
                return

            // Here, we create a CameraCaptureSession for camera preview.
            if (Build.VERSION.SDK_INT >= 28) {
                val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                        Arrays.asList(OutputConfiguration(surface), OutputConfiguration(imageSurface)),
                        params.backgroundExecutor, PreviewSessionStateCallback(activity, params))

                camera.createCaptureSession(sessionConfig)

            } else {
                camera.createCaptureSession(Arrays.asList(surface, imageSurface),
                        PreviewSessionStateCallback(activity, params), params.backgroundHandler)
            }

        }

    } catch (e: CameraAccessException) {
        e.printStackTrace()
    } catch (e: IllegalStateException) {
        Logd("createCameraPreviewSession IllegalStateException, aborting: " + e)
        //camera2Abort(activity, params, testConfig)
    }
}

fun camera2OpenCamera(activity: MainActivity, params: CameraParams?) {
    if (null == params)
        return

    val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    try {
        //We might be running a new test so make sure our callbacks match the test config
        params.cameraCallback = CameraStateCallback(params, activity)
        params.captureCallback = FocusCaptureSessionCallback(activity, params)

        //We have a dual lens situation, only open logical cam
        if (!MainActivity.dualCamLogicalId.equals("")
            && MainActivity.dualCamLogicalId.equals(params.id)) {
            Logd("Open Logical Camera backed by 2+ physical streams: " + MainActivity.dualCamLogicalId)

            if (28 <= Build.VERSION.SDK_INT)
                manager.openCamera(params.id, params.backgroundExecutor, params.cameraCallback)
            else
                manager.openCamera(params.id, params.cameraCallback, params.backgroundHandler)

        } else {
            Logd("openCamera: " + params.id)
            if (28 <= Build.VERSION.SDK_INT)
                manager.openCamera(params.id, params.backgroundExecutor, params.cameraCallback)
            else
                manager.openCamera(params.id, params.cameraCallback, params.backgroundHandler)
        }

    } catch (e: CameraAccessException) {
        Logd("openCamera CameraAccessException: " + params.id)
        e.printStackTrace()
    } catch (e: SecurityException) {
        Logd("openCamera SecurityException: " + params.id)
        e.printStackTrace()
    }
}


//Close the first open camera we find
fun closeACamera(activity: MainActivity) {
    var closedACamera = false
    Logd("In closeACamera, looking for open camera.")
    for (tempCameraParams: CameraParams in cameraParams.values) {
        if (tempCameraParams.isOpen) {
            Logd("In closeACamera, found open camera, closing: " + tempCameraParams.id)
            closedACamera = true
            closeCamera(tempCameraParams, activity)
            break
        }
    }

    // We couldn't find an open camera, let's close everything
    if (!closedACamera) {
        closeAllCameras(activity)
    }
}

fun closeAllCameras(activity: MainActivity) {
    Logd("Closing all cameras.")
    for (tempCameraParams: CameraParams in cameraParams.values) {
        closeCamera(tempCameraParams, activity)
    }
}

fun closeCamera(params: CameraParams?, activity: MainActivity) {
    if (null == params)
        return

    Logd("closeCamera: " + params.id)
    params.isOpen = false
    params.captureSession?.close()
    params.device?.close()
}



fun takePicture(activity: MainActivity, params: CameraParams) {
    Logd("TakePicture: capture start.")

    if (!params.isOpen) {
        //camera2Abort(activity, params, testConfig)
        return
    }

    lockFocus(activity, params)
}

fun lockFocus(activity: MainActivity, params: CameraParams) {
    Logd("In lockFocus.")
    if (!params.isOpen) {
        //camera2Abort(activity, params, testConfig)
        return
    }

    try {
        val camera = params.captureSession?.getDevice()
        if (null != camera) {
            params.captureBuilder?.addTarget(params.imageReader?.surface)
            setAutoFlash(activity, camera, params.captureBuilder)

            //If this lens can focus, we need to start a focus search and wait for focus lock
            if (params.hasAF) {
                Logd("In lockFocus. About to request focus lock and call capture.")
//                params.captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
//                setAutoFlash(activity, camera, params.captureBuilder)
//                params.captureBuilder?.addTarget(params.imageReader?.getSurface())
//                params.captureBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
//                        CameraMetadata.CONTROL_AF_TRIGGER_START);
 //               params.captureBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
//                params.captureBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                params.state = STATE_PICTURE_TAKEN
                captureStillPicture(activity, params)


//                params.state = STATE_WAITING_LOCK
//                params.captureSession?.capture(params.captureBuilder?.build(), params.captureCallback,
 //                       params.backgroundHandler)

                //Otherwise, a fixed focus lens so we can go straight to taking the image
            } else {
                Logd("In lockFocus. Fixed focus lens about call captureStillPicture.")
                params.state = STATE_PICTURE_TAKEN
                captureStillPicture(activity, params)
            }
        }

    } catch (e: CameraAccessException) {
        e.printStackTrace()
    }
}

fun runPrecaptureSequence(activity: MainActivity, params: CameraParams) {
    if (!params.isOpen) {
        //camera2Abort(activity, params, testConfig)
        return
    }

    try {
        val camera = params.captureSession?.getDevice()

        if (null != camera) {
            setAutoFlash(activity, camera, params.captureBuilder)
            params.captureBuilder?.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)

            params.state = STATE_WAITING_PRECAPTURE

            if (28 <= Build.VERSION.SDK_INT)
                params.captureSession?.captureSingleRequest(params.captureBuilder?.build(), params.backgroundExecutor, params.captureCallback)
            else
                params.captureSession?.capture(params.captureBuilder?.build(), params.captureCallback,
                        params.backgroundHandler)

        }
    } catch (e: CameraAccessException) {
        e.printStackTrace()
    }

}

fun captureStillPicture(activity: MainActivity, params: CameraParams) {
    if (!params.isOpen) {
        //camera2Abort(activity, params, testConfig)
        return
    }

    try {
        Logd("In captureStillPicture.")

        val camera = params.captureSession?.getDevice()

        if (null != camera) {
            params.captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            params.captureBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
//            params.captureBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
//            setAutoFlash(activity, camera, params.captureBuilder)

            if (params.id.equals(dualCamLogicalId) && twoLens.isTwoLensShot) {

                val normalParams: CameraParams? = MainActivity.cameraParams.get(normalLensId)
                val wideParams: CameraParams? = MainActivity.cameraParams.get(wideAngleId)

                if (null == normalParams || null == wideParams)
                    return

                Logd("In captureStillPicture. This is a Dual Cam shot.")

                params.captureBuilder?.addTarget(normalParams.imageReader?.surface!!)
                params.captureBuilder?.addTarget(wideParams.imageReader?.surface!!)

            } else {
                //Default to wide
                val wideParams: CameraParams? = MainActivity.cameraParams.get(wideAngleId)
                if (null != wideParams)
                    params.captureBuilder?.addTarget(wideParams.imageReader?.surface!!)
                else
                    params.captureBuilder?.addTarget(params.imageReader?.getSurface())
            }

//            if (params.hasAF) {
//                    params.captureBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
//                            CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
//            }

            //Let's add a sepia effect for fun
            //Only for 2-camera case and the background
/*            if (params.hasSepia)
                params.captureBuilder?.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_SEPIA)
            // Or mono if we don't have sepia
            else if (params.hasMono)
                params.captureBuilder?.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_MONO)
*/

            //Otherwise too dark
//            if (params.id == MainActivity.wideAngleId)
//                params.captureBuilder?.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 5);
//            else
            params.captureBuilder?.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 4);


            params.captureBuilder?.set(CaptureRequest.JPEG_QUALITY, PrefHelper.getQuality(activity))

            //We are going to try and correct distortion, so we disable automatic correction
            //This should disable HDR+ as well
            if (Build.VERSION.SDK_INT >= 28 && PrefHelper.getDualCam(activity)) {
                params.captureBuilder?.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
                //This is REQUIRED to disable HDR+ - even though Pixel 3 doesn't have sepia
                params.captureBuilder?.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_SEPIA)
            } else {
                //This is REQUIRED to disable HDR+ - even though Pixel 3 doesn't have sepia
                params.captureBuilder?.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_SEPIA)
                Logd("DUAL CAM DEBUG: I am setting sepia mode.")
//            Logd("DUAL CAM DEBUG: I am NOT setting sepia mode.")
            }

            // Request face detection
            if (CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF != params.bestFaceDetectionMode)
                params.captureBuilder?.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, params.bestFaceDetectionMode)
//            Logd("FACE-DETECT DEBUG: I am setting face-detect mode to: " + params.bestFaceDetectionMode)

            // Orientation
            val rotation = activity.getWindowManager().getDefaultDisplay().getRotation()
            var capturedImageRotation = getOrientation(params, rotation)
            params.captureBuilder?.set(CaptureRequest.JPEG_ORIENTATION, capturedImageRotation)

            try {
                params.captureSession?.stopRepeating()
//                params.captureSession?.abortCaptures()
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }

            //Do the capture
            if (28 <= Build.VERSION.SDK_INT)
                params.captureSession?.captureSingleRequest(params.captureBuilder?.build(), params.backgroundExecutor, StillCaptureSessionCallback(activity, params))
            else
                params.captureSession?.capture(params.captureBuilder?.build(), StillCaptureSessionCallback(activity, params),
                        params.backgroundHandler)
        }
    } catch (e: CameraAccessException) {
        e.printStackTrace()

    } catch (e: IllegalStateException) {
        Logd("captureStillPicture IllegalStateException, aborting: " + e)
        //camera2Abort(activity, params, testConfig)
    }
}

fun unlockFocus(activity: MainActivity, params: CameraParams) {
    Logd("In unlockFocus.")

    if (!params.isOpen) {
        //camera2Abort(activity, params, testConfig)
        return
    }

    try {
        if (null != params.device) {
                // Reset auto-focus
//                params.captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_TRIGGER,
//                        CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
//                params.captureSession?.capture(params.captureRequestBuilder?.build(), params.captureCallback,
//                        params.backgroundHandler)
//                createCameraPreviewSession(activity, camera, params, testConfig)
        }
    } catch (e: CameraAccessException) {
        e.printStackTrace()

    } catch (e: IllegalStateException) {
        Logd("unlockFocus IllegalStateException, aborting: " + e)
        // camera2Abort(activity, params, testConfig)
    }

}


internal fun shutterControl(activity: Activity, shutter: View, openShutter: Boolean) {
/*    activity.runOnUiThread {
        if (openShutter)
            shutter.visibility = View.INVISIBLE
        else
            shutter.visibility = View.VISIBLE
    }
*/
}

fun camera2Abort(activity: MainActivity, params: CameraParams) {
//    activity.stopBackgroundThread(params)
}