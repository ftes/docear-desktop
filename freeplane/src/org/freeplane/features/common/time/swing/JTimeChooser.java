package org.freeplane.features.common.time.swing;import java.awt.Dimension;import java.beans.PropertyChangeEvent;import java.beans.PropertyChangeListener;import java.util.Calendar;import javax.swing.Box;import javax.swing.BoxLayout;import javax.swing.JLabel;public class JTimeChooser extends Box{	/**	 * 	 */	private static final long serialVersionUID = 1L;	public static final String YEAR_PROPERTY = "year";	final private JSpinField hourChooser;	final private JSpinField minuteChooser;	private Calendar calendar;	Calendar getCalendar() {    	return calendar;    }	void setCalendar(Calendar calendar) {    	this.calendar = calendar;    	hourChooser.setValue(calendar.get(Calendar.HOUR_OF_DAY));    	minuteChooser.setValue(calendar.get(Calendar.MINUTE));    }	/**	 * Default JCalendar constructor.	 */	public JTimeChooser() {		super(BoxLayout.X_AXIS);		setName("JTimeChooser");		hourChooser  = new JSpinField(0, 59);		hourChooser.addPropertyChangeListener(new PropertyChangeListener() {			public void propertyChange(PropertyChangeEvent e) {				if(e.getPropertyName().equals("value") &&  calendar != null){					calendar.set(Calendar.HOUR_OF_DAY, (Integer) hourChooser.getValue());				}			}		});		hourChooser.adjustWidthToMaximumValue();		minuteChooser  = new JSpinField(0, 59);		minuteChooser.setMinWidth(2);		minuteChooser.addPropertyChangeListener(new PropertyChangeListener() {			public void propertyChange(PropertyChangeEvent e) {				if(e.getPropertyName().equals("value") &&  calendar != null){					calendar.set(Calendar.MINUTE, (Integer) minuteChooser.getValue());				}			}		});		minuteChooser.adjustWidthToMaximumValue();		final Dimension preferredSize = minuteChooser.getPreferredSize();		minuteChooser.setPreferredSize(preferredSize);		minuteChooser.setMaximumSize(preferredSize);		hourChooser.setPreferredSize(preferredSize);		hourChooser.setMaximumSize(preferredSize);		setCalendar(Calendar.getInstance());		add(Box.createHorizontalGlue());		add(hourChooser);		add(new JLabel(":"));		add(minuteChooser);		add(Box.createHorizontalGlue());	}	public int getHour() {		return (Integer) hourChooser.getValue();	}	public int getMinute() {		return (Integer) minuteChooser.getValue();	}}