package com.mywearapp

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "MyWearApp"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
    object : ReactActivityDelegate(this, mainComponentName) {}
}