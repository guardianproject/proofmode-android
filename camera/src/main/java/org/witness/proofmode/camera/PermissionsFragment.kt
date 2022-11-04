package org.witness.proofmode.camera

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.witness.proofmode.camera.databinding.FragmentPermissionsBinding

class PermissionsFragment : Fragment() {
    private var _binding: FragmentPermissionsBinding? = null

    private val binding get() = _binding!!


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPermissionsBinding.inflate(layoutInflater, container, false)
        binding.lifecycleOwner = this
        binding.buttonRequestPermissions.setOnClickListener {
            requestPermissions()
        }
        return binding.root
    }

    private fun requestPermissions() {
        requestAllPermissions(
            this, cameraPermissions.toTypedArray(),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkPermissionsAndNavigate()
    }


    private fun checkPermissionsAndNavigate() {
        if (hasAllPermissions(requireContext(), cameraPermissions.toTypedArray())) {
         //   val directions =
           //     PermissionsFragmentDirections.actionPermissionsFragmentToCameraFragment()
           // findNavController().navigate(directions)
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated(message = "Do not use in further releases")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty()) {
             //   findNavController().navigate(PermissionsFragmentDirections.actionPermissionsFragmentToCameraFragment())
            } else {
                AlertDialog.Builder(requireContext())
                    .setMessage("Enable permissions in settings to be able to use  camera")
                    .setPositiveButton(
                        android.R.string.ok
                    ) { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", requireContext().packageName, null)
                        intent.data = uri
                        intent.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                            startActivity(this)
                        }

                    }
                    .setNegativeButton(android.R.string.no) { _, _ ->
                        Toast.makeText(
                            requireContext(),
                            "Permissions not granted. You wont be able to use all camera functionalities",
                            Toast.LENGTH_SHORT
                        )
                            .show()
                    }
                    .show()
            }
        }
    }
}