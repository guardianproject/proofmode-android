package org.witness.proofmode.camera

import android.widget.MediaController
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import org.witness.proofmode.camera.databinding.FragmentViewCapturedBinding


class ViewCapturedFragment : Fragment() {
    private val cameraVModel:CameraViewModel by activityViewModels()
    private lateinit var binding: FragmentViewCapturedBinding
    private lateinit var mediaController: MediaController

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentViewCapturedBinding.inflate(inflater, container, false)
        binding.viewModel = cameraVModel
        binding.lifecycleOwner = this
        mediaController = MediaController(requireContext())
        observeVideoUriChanges()
        return binding.root
    }

    private fun observeVideoUriChanges() {
        cameraVModel.videoUri.observe(viewLifecycleOwner) {
            mediaController.setAnchorView(binding.videoView)
            binding.videoView.start()
            binding.videoView.setOnCompletionListener {

            }
        }
    }

    companion object {

    }
}