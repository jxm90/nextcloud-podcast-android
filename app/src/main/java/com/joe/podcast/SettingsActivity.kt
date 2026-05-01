package com.joe.podcast

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.joe.podcast.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SettingsStore(this)

        binding.serverUrlEdit.setText(prefs.serverUrl)
        binding.usernameEdit.setText(prefs.username)
        binding.appPasswordEdit.setText(prefs.appPassword)
        binding.downloadFolderEdit.setText(prefs.downloadFolder)

        binding.saveButton.setOnClickListener {
            val url = binding.serverUrlEdit.text.toString().trim().trimEnd('/')
            val user = binding.usernameEdit.text.toString().trim()
            val pass = binding.appPasswordEdit.text.toString().trim()
            if (url.isBlank() || user.isBlank() || pass.isBlank()) {
                Toast.makeText(this, "Fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.serverUrl = url
            prefs.username = user
            prefs.appPassword = pass
            prefs.downloadFolder = binding.downloadFolderEdit.text?.toString()?.trim().orEmpty()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
