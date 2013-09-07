package com.cgutman.shieldproxy;

import java.io.IOException;

import com.cgutman.shieldproxy.relay.RelayService;
import com.cgutman.shieldproxy.relay.RelayService.MdnsBinder;

import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;

public class Configuration extends Activity {
	
	private MdnsBinder binder;
	private Button statusButton;
	
	private TextView hostText;
	private TextView portText;
	
	private SharedPreferences prefs;
	private static final String HOST_PREF = "host";
	private static final String PORT_PREF = "port";
	private static final String DEFAULT_PORT_PREF = "5354";

	private ServiceConnection connection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
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
	
	private void updateStatus() {
		if (binder != null && binder.isRunning())
		{
			statusButton.setText("Stop");
		}
		else
		{
			startService(new Intent(Configuration.this, RelayService.class));
			bindService(new Intent(Configuration.this, RelayService.class), connection, 0);
			statusButton.setText("Start");
		}
	}
	
	@Override
	public void onResume() {
		super.onResume();
		updateStatus();
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_configuration);
		
		statusButton = (Button) findViewById(R.id.statusButton);
		hostText = (TextView) findViewById(R.id.hostTextView);
		portText = (TextView) findViewById(R.id.portTextView);
		
		prefs = getPreferences(0);
		hostText.setText(prefs.getString(Configuration.HOST_PREF, ""));
		portText.setText(prefs.getString(Configuration.PORT_PREF, Configuration.DEFAULT_PORT_PREF));
		
		statusButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				if (binder != null) {
					if (!binder.isRunning())
					{
						int port;
						try {
							port = Integer.parseInt(portText.getText().toString());
						} catch (NumberFormatException e) {
							Toast.makeText(Configuration.this, e.getMessage(), Toast.LENGTH_LONG).show();
							return;
						}
						binder.configureRelay(hostText.getText().toString(), port);
						try {
							binder.startRelay();
						} catch (IOException e) {
							Toast.makeText(Configuration.this, e.getMessage(), Toast.LENGTH_LONG).show();
							return;
						}
					}
					else
					{
						binder.stopRelay();
					}
					
					updateStatus();
				} else {
					startService(new Intent(Configuration.this, RelayService.class));
					bindService(new Intent(Configuration.this, RelayService.class), connection, 0);
				}
			}
		});
	}
	
	@Override
	protected void onDestroy() {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(Configuration.HOST_PREF, hostText.getText().toString());
		editor.putString(Configuration.PORT_PREF, portText.getText().toString());
		editor.apply();
		
		unbindService(connection);
		
		super.onDestroy();
	}
}
