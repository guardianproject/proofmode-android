package org.witness.proofmode.camera

import android.app.Dialog
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.ImageCapture
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import org.witness.proofmode.camera.databinding.FlashSettingsLayoutBinding

class FlashSettingsDialogFragment: DialogFragment() {

    private lateinit var binding:FlashSettingsLayoutBinding
    private val viewModel:FlashModeViewModel by activityViewModels()
    private lateinit var flashOnButton:ImageButton
    private lateinit var flashOffButton:ImageButton
    private lateinit var flashAutoButton:ImageButton
    private lateinit var preferences: SharedPreferences

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        preferences = requireActivity().getSharedPreferences(PREFS_FILE_NAME,MODE_PRIVATE)
        val mode = preferences.getInt(FLASH_KEY,ImageCapture.FLASH_MODE_OFF)

        return activity?.let {
            val builder = AlertDialog.Builder(it)
            val inflater = requireActivity().layoutInflater
            binding = FlashSettingsLayoutBinding.inflate(inflater)
            binding.viewModel = viewModel
            binding.lifecycleOwner = this@FlashSettingsDialogFragment
            builder.setView(binding.root)
            initViews()
            initListeners()
            observeModeChange()
            // If mode is 100, enable torch
            setFlashMode(mode)

            builder.create()
        }?: throw IllegalStateException("Activity cannot be null")


    }

    private fun setPreferenceSetting(mode: Int) {
        preferences.edit().putInt(FLASH_KEY,mode)
            .apply()
    }

    private fun setFlashMode(mode:Int) {
        viewModel.setFlashMode(mode)
    }

    private fun observeModeChange() {
        viewModel.flashMode.observe(this) { mode ->
            when (mode) {
                ImageCapture.FLASH_MODE_ON -> {
                    viewModel.tintButton(
                        flashOnButton,
                        R.color.colorAccent,
                        requireContext()
                    )
                    viewModel.tintButton(flashAutoButton,android.R.color.black,requireContext())
                    viewModel.tintButton(flashOffButton,android.R.color.black,requireContext())


                }

                ImageCapture.FLASH_MODE_AUTO -> {
                    viewModel.tintButton(
                        flashOnButton,
                        android.R.color.black,
                        requireContext()
                    )

                    viewModel.tintButton(flashAutoButton,R.color.colorAccent,requireContext())
                    viewModel.tintButton(flashOffButton,android.R.color.black,requireContext())
                }
                ImageCapture.FLASH_MODE_OFF -> {
                    viewModel.tintButton(
                        flashOnButton,
                        android.R.color.black,
                        requireContext()
                    )

                    viewModel.tintButton(flashAutoButton,android.R.color.black,requireContext())
                    viewModel.tintButton(flashOffButton,R.color.colorAccent,requireContext())

                }

                100 -> {
                    viewModel.tintButton(
                        flashOnButton,
                        android.R.color.black,
                        requireContext()
                    )

                    viewModel.tintButton(flashAutoButton,android.R.color.black,requireContext())
                    viewModel.tintButton(flashOffButton,R.color.colorAccent,requireContext())
                }

            }
        }
    }
    private fun initViews() {
        binding.apply {
            flashAutoButton = buttonFlashAuto
            flashOffButton = buttonFlashOff
            flashOnButton = buttonFlashOn
        }
    }

    private fun initListeners() {
        flashAutoButton.setOnClickListener {

            setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            setPreferenceSetting(ImageCapture.FLASH_MODE_AUTO)
            dismiss()
        }
        flashOffButton.setOnClickListener {
            setFlashMode(ImageCapture.FLASH_MODE_OFF)
            setPreferenceSetting(ImageCapture.FLASH_MODE_OFF)
            dismiss()
        }

        flashOnButton.setOnClickListener {
            setFlashMode(ImageCapture.FLASH_MODE_ON)
            setPreferenceSetting(ImageCapture.FLASH_MODE_ON)
            dismiss()
        }


    }
}