package com.cgutman.shieldproxy;

import java.io.IOException;

import com.cgutman.shieldproxy.relay.RelayService;
import com.cgutman.shieldproxy.relay.RelayService.MdnsBinder;

import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;

public class Configuration extends Activity implements TextWatcher {
	
	private MdnsBinder binder;
	private Button statusButton;
	
	private EditText hostText;
	private EditText portText;
	
	private SharedPreferences prefs;
	private static final String HOST_PREF = "host";
	private static final String PORT_PREF = "port";
	private static final String DEFAULT_PORT_PREF = "5354";

	private ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			// When we get connected, register ourselves as an exception listener
			// so we can update the service status if something bad happens
			binder = (MdnsBinder)service;
			binder.setListener(new RelayService.Listener() {
				@Override
				public void onRelayException(Exception e) {
					updateStatus();
				}
			});
			updateStatus();
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			binder = null;
		}
	};
	
	private boolean serviceNeedsConfigChange() {
		// The service needs a config change if it is running and the textbox values no longer
		// reflect the current configuration of the service
		return binder != null && binder.isRunning() &&
				(!binder.getRelayPeer().equals(hostText.getText().toString()) ||
				!String.valueOf(binder.getRelayPort()).equals(portText.getText().toString()));
	}
	
	private void updateStatus() {
		if (binder != null && binder.isRunning())
		{
			if (serviceNeedsConfigChange())
			{
				// The service is running, but not with the config specified in the activity
				statusButton.setText("Apply New Configuration");
			}
			else
			{
				// The service is running
				statusButton.setText("Stop");
			}
		}
		else
		{
			if (binder == null)
			{
				// We're not even bound yet
				startService(new Intent(Configuration.this, RelayService.class));
				bindService(new Intent(Configuration.this, RelayService.class), connection, 0);
			}
			
			// Service is stopped
			statusButton.setText("Start");
		}
	}
	
	@Override
	public void onResume() {
		super.onResume();
		
		// Update the service status when the activity is resumed
		updateStatus();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_configuration);
		
		statusButton = (Button) findViewById(R.id.statusButton);
		hostText = (EditText) findViewById(R.id.hostTextView);
		portText = (EditText) findViewById(R.id.portTextView);
		
		// Listen for text updates
		hostText.addTextChangedListener(this);
		portText.addTextChangedListener(this);
		
		// Load the previous values
		prefs = getPreferences(0);
		hostText.setText(prefs.getString(Configuration.HOST_PREF, ""));
		portText.setText(prefs.getString(Configuration.PORT_PREF, Configuration.DEFAULT_PORT_PREF));
		
		statusButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				if (binder != null) {
					// Stop first if we're doing a config update
					if (serviceNeedsConfigChange())
						binder.stopRelay();
					
					// This acts as a start/stop button
					if (!binder.isRunning())
					{
						// It's not running; let's start it
						
						// Parse the integer from the port field
						int port;
						try {
							port = Integer.parseInt(portText.getText().toString());
						} catch (NumberFormatException e) {
							Toast.makeText(Configuration.this, e.getMessage(), Toast.LENGTH_LONG).show();
							return;
						}
						
						// Reconfigure the relay service to point to the new host
						binder.configureRelay(hostText.getText().toString(), port);
						
						// Start the relay service
						try {
							binder.startRelay();
						} catch (IOException e) {
							Toast.makeText(Configuration.this, e.getMessage(), Toast.LENGTH_LONG).show();
							return;
						}
					}
					else
					{
						// The service is running; we should stop it
						binder.stopRelay();
					}
					
					// Update the button text
					updateStatus();
				} else {
					// We're not even bound to the service yet; try to bind now
					startService(new Intent(Configuration.this, RelayService.class));
					bindService(new Intent(Configuration.this, RelayService.class), connection, 0);
				}
			}
		});
	}
	
	@Override
	public void onPause() {
		// Save the current values of the text boxes
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(Configuration.HOST_PREF, hostText.getText().toString());
		editor.putString(Configuration.PORT_PREF, portText.getText().toString());
		editor.apply();
		
		super.onPause();
	}
	
	@Override
	protected void onDestroy() {
		// Remove our listener since we're dying shortly
		if (binder != null) {
			binder.setListener(null);
		}

		// Unbind our connection from the service (service will continue running)
		unbindService(connection);
		
		super.onDestroy();
	}

	@Override
	public void afterTextChanged(Editable s) {
		// Update the button text based on the updated edit text contents
		updateStatus();
	}

	@Override
	public void beforeTextChanged(CharSequence arg0, int arg1, int arg2,
			int arg3) {
	}

	@Override
	public void onTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) {
	}
}
