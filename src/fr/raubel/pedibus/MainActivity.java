package fr.raubel.pedibus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.Toast;

class DateFormat {
	
	private static SimpleDateFormat sdfYMD = new SimpleDateFormat("yyyy/MM/dd");
	private static SimpleDateFormat sdfDM = new SimpleDateFormat("dd/MM", Locale.FRANCE);
	
	public static synchronized String format(Calendar cal) {
		return sdfYMD.format(cal.getTime());
	}
	
	public static synchronized String formatDM(Calendar cal) {
		return sdfDM.format(cal.getTime());
	}
	
	public static synchronized Calendar parse(String str) throws ParseException {
		Calendar day = GregorianCalendar.getInstance();
		day.setTime(sdfYMD.parse(str));
		return day;
	}
}

public class MainActivity extends Activity {
	
	private static final String TAG = "Pedibus";
	private static final String GUIDES_FILENAME = "Pedibus/guides.txt";
	private static final String HISTO_FILENAME = "Pedibus/histo.txt";
	
	private static class Guide {
		final private String name, phoneNumber;
		private Guide(String name, String phoneNumber) {
			this.name = name;
			this.phoneNumber = phoneNumber;
		}
		private static final Guide NONE = new Guide("Aucun", "");
		
		@Override
		public boolean equals(Object o) {
			if (o == null || !(o instanceof Guide))
				return false;
			Guide g = (Guide)o;
			if (g.name == null)
				return name == g.name;
			return g.name.equals(name);
		}
		
		@Override
		public int hashCode() {
			return (name != null ? name.hashCode() : 0) + 37*(phoneNumber != null ? phoneNumber.hashCode() : 0);
		}
		
		@Override
		public String toString() {
		    return name;
		}
	}
	
	private static class Holder {
		final String day;
		final Guide guide;
		Holder(String day, Guide guide) { this.day = day; this.guide = guide; }
	}
	
	abstract private class GuideAdapter extends ArrayAdapter<Guide> {
	    public GuideAdapter() {
	        super(context, android.R.layout.simple_dropdown_item_1line);
	    }

	}
	
	private final Context context = this;
	private static final int[] labelsId = { R.id.mondayLabel, R.id.tuesdayLabel, R.id.thursdayLabel, R.id.fridayLabel };
	private static final int[] spinnersId = { R.id.mondaySpinner, R.id.tuesdaySpinner, R.id.thursdaySpinner, R.id.fridaySpinner };
	private final List<Guide> guides = loadGuides(GUIDES_FILENAME);
	private final Map<String, String> histo = loadHisto(HISTO_FILENAME);
	private Calendar friday = nextFriday();
	private Handler handler = new Handler();
	private SpinnerAdapter adapter;
	
	@Override
    public void onCreate(Bundle savedInstanceState) {
		
    	super.onCreate(savedInstanceState);
    	
    	setContentView(R.layout.main);
    	
    	((ImageButton)findViewById(R.id.prev)).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				friday.add(Calendar.DAY_OF_YEAR, -7);
				updateView();
			}
		});
    	
    	((ImageButton)findViewById(R.id.next)).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				friday.add(Calendar.DAY_OF_YEAR, 7);
				updateView();
			}
		});
    	
    	adapter = new GuideAdapter() {
            @Override
            public int getCount() {
                return guides.size();
            }

            @Override
            public Guide getItem(int position) {
                return guides.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View view, ViewGroup parent) {
                TextView text = new TextView(MainActivity.this);
                text.setText(guides.get(position).name);
                return text;
            }

		};
		for (int spinnerId: spinnersId) {
			Spinner spinner = (Spinner)findViewById(spinnerId);
			spinner.setAdapter(adapter);
			spinner.setOnItemSelectedListener(new OnItemSelectedListener() {

				@Override
				public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
					Guide guide = guides.get(pos);
					Holder holder = (Holder)parent.getTag();
					if (holder == null || holder.guide.equals(guide))
						return;
					parent.setTag(new Holder(holder.day, guide));
					if (pos == 0)
						histo.remove(holder.day);
					else
						histo.put(holder.day, guide.name);
					updateView();
				}

				@Override
				public void onNothingSelected(AdapterView<?> parent) {}
			});
		}

    	updateView();
    	
    	((Button)findViewById(R.id.save)).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View view) {
				saveHisto(HISTO_FILENAME);
				toast("Fichier historique sauvegardé");
			}
    	});
    	
    	((Button)findViewById(R.id.saveAndSend)).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View view) {
		        new AlertDialog.Builder(context)
		        .setIcon(android.R.drawable.ic_dialog_alert)
		        .setTitle(R.string.confirm_title)
		        .setMessage(R.string.confirm_message)
		        .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
		            @Override
		            public void onClick(DialogInterface dialog, int which) {
		            	saveHisto(HISTO_FILENAME);
		            	sendMessage();
		            }

		        })
		        .setNegativeButton(R.string.no, null)
		        .show();
			}
		});
    }
	
	private List<Guide> loadGuides(String filename) {
		List<Guide> guides = new LinkedList<Guide>();
		guides.add(Guide.NONE);
		File file = new File(Environment.getExternalStorageDirectory(), filename);
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new FileReader(file));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.trim().length() == 0)
					continue;
				
				String[] fields = line.split("\\s+");
				String name = fields[0];
				String phoneNumber = fields.length > 1 ? fields[1] : "";
				guides.add(new Guide(name, phoneNumber));
			}
		} catch (IOException e) {
			toast(filename + ": cannot be read (" + e.getMessage() + ")");
		} finally {
			try { reader.close(); } catch (Exception ignore) {}
		}
		
		return guides;
	}
	
	private Map<String, String> loadHisto(String filename) {
		Map<String, String> map = new TreeMap<String, String>();
		File file = new File(Environment.getExternalStorageDirectory(), filename);
		BufferedReader reader = null;
		int lineNumber = 0;
		try {
			reader = new BufferedReader(new FileReader(file));
			String line;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.trim().length() == 0)
					continue;
				
				String[] fields = line.split("\\s+");
				if (fields.length != 2)
					throw new Exception("error at line " + lineNumber);
				
				String day = fields[0];
				String guideName = fields[1];
				map.put(day, guideName);
			}
		} catch (Exception e) {
			toast(filename + ": cannot be read (" + e.getMessage() + ")");
		} finally {
			try { reader.close(); } catch (Exception ignore) {}
		}
		return map;
	}
	
	private void saveHisto(String filename) {
		File file = new File(Environment.getExternalStorageDirectory(), filename);
		PrintStream stream = null;
		try {
			stream = new PrintStream(new FileOutputStream(file));
			for (Map.Entry<String, String> entry: histo.entrySet())
				stream.println(entry.getKey() + " " + entry.getValue());
		} catch (Exception e) {
			toast(filename + ": cannot be written (" + e.getMessage() + ")");
		} finally {
			try { stream.close(); } catch (Exception ignore) {}
		}
	}
    
    private void setLabelAndSpinner(int labelId, int spinnerId, Calendar day) {
    	
    	TextView textView = (TextView)findViewById(labelId);
    	textView.setText(DateFormat.formatDM(day));
    	textView.setTag(day);
    	
    	String guideName = histo.get(DateFormat.format(day));
    	int pos = getGuidePosition(guideName);
    	log("Position for guide " + guideName + ": " + pos);
    	Spinner spinner = (Spinner)findViewById(spinnerId);
    	spinner.setSelection(pos);
    	spinner.setTag(new Holder(DateFormat.format(day), guides.get(pos)));

    }
    
    private int getGuidePosition(String guideName) {
    	
    	// First position is for NONE
    	if (guideName == null)
    		return 0;

    	for (int i = 1; i < guides.size(); i++)
    		if (guideName.equals(guides.get(i).name))
    			return i;
    			
    	return 0;
    }
    
    private void updateView() {
    	setLabelAndSpinner(R.id.mondayLabel, R.id.mondaySpinner, previous(Calendar.MONDAY, friday));
    	setLabelAndSpinner(R.id.tuesdayLabel, R.id.tuesdaySpinner, previous(Calendar.TUESDAY, friday));
    	setLabelAndSpinner(R.id.thursdayLabel, R.id.thursdaySpinner, previous(Calendar.THURSDAY, friday));
    	setLabelAndSpinner(R.id.fridayLabel, R.id.fridaySpinner, previous(Calendar.FRIDAY, friday));
    	
    	String message = "Planning pédibus :\n";
    	for (int i = 0; i < labelsId.length; i++) {
    	    TextView view = (TextView)findViewById(labelsId[i]);
    		String date = view.getText().toString();
    		Spinner spinner = (Spinner)findViewById(spinnersId[i]);
    		String guideName = spinner.getTag() != null ? ((Holder)spinner.getTag()).guide.name : "?";
    		if (guideName.equals(Guide.NONE.name))
    			continue; // Not a pedibus day!
    		message += "- " + date + " : " + guideName + " (" + numberOfDays(guideName, (Calendar)view.getTag()) + ")\n";
    	}
    	message += "\nBon pédibus à tous !";
    	((EditText)findViewById(R.id.message)).setText(message);
    	
    	((TextView)findViewById(R.id.weekLabel)).setText("Semaine " + friday.get(Calendar.WEEK_OF_YEAR));
    	
    }
    
    private int numberOfDays(String guideName, Calendar day) {
    	int nb = 0;
    	// Consider only past events
    	String limit = DateFormat.format(day /* GregorianCalendar.getInstance() */);
    	for (Map.Entry<String, String> entry: histo.entrySet())
    	    if (limit.compareTo(entry.getKey()) >= 0 && guideName.equals(entry.getValue()))
    	        nb++;
    	return nb;
    }
    
    private void sendMessage() {
    	int nb = 0;
    	String recipients = "";
    	String message = ((EditText)findViewById(R.id.message)).getText().toString();
    	for (Guide guide: guides)
    		if (guide.phoneNumber.matches("^06\\d{8}$")) {
    			nb++;
    			recipients += guide.name + " ";
    			SmsManager.getDefault().sendTextMessage(guide.phoneNumber, null, message, null, null);
    		}
    	toast("SMS sent to " + recipients + "(" + nb + " recipient(s))");
    }

    private Calendar nextFriday() {
    	Calendar cal = GregorianCalendar.getInstance();
    	while (true) {
    		if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY)
    			return cal;
    		cal.add(Calendar.DAY_OF_YEAR, 1);
    	}
    }
    
    private Calendar previous(int field, Calendar org) {
    	Calendar cal = (Calendar)org.clone();
    	while (true) {
    		if (cal.get(Calendar.DAY_OF_WEEK) == field)
    			return cal;
    		cal.add(Calendar.DAY_OF_YEAR, -1);
    	}
    }
    
    private void log(final String message) {
    	Log.d(TAG, message);
    }
    
    private void toast(final String message) {
    	log(message);
    	handler.post(new Runnable() {
			public void run() {
	    		Toast.makeText(context, message, Toast.LENGTH_LONG).show();
			}
		});
    }
}
