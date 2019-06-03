package mv2h.tools;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;

import mv2h.objects.Note;
import mv2h.objects.harmony.Key;
import mv2h.objects.meter.Hierarchy;
import mv2h.objects.meter.Tatum;

/**
 * The <code>Converter</code> class is used to convert a given output from the MusicXMLParser
 * (read from standard in) into a format that can be read by the MV2H package (standard out).
 * 
 * @author Andrew McLeod
 */
public class Converter {
	
	/**
	 * The notes present in the XML piece.
	 */
	private List<Note> notes = new ArrayList<Note>();
	
	/**
	 * The hierarchy of this piece.
	 */
	private Hierarchy hierarchy = null;
	
	/**
	 * A list of the key signatures of this piece.
	 */
	private List<Key> keys = new ArrayList<Key>();
	
	/**
	 * A list of the notes for which there hasn't yet been an offset.
	 */
	private List<Note> unfinishedNotes = new ArrayList<Note>();
	
	/**
	 * The last tick of the piece.
	 */
	private int lastTick = 0;
	
	/**
	 * The first tick of the piece.
	 */
	private int firstTick = Integer.MAX_VALUE;
	
	/**
	 * Run the program, reading the MusicXMLParser output from standard in and printing to
	 * standard out.
	 * 
	 * @param args Unused command line arguments.
	 */
	public static void main(String[] args) {
		System.out.println(new Converter(System.in));
	}
	
	/**
	 * Create a new Converter object by parsing the input from the MusicXMLParser.
	 * <br>
	 * This method contains the main program logic, and printing is handled by
	 * {@link #toString()}.
	 * 
	 * @param stream The MusicXMLParser output to convert.
	 */
	public Converter(InputStream stream) {
		Scanner in = new Scanner(stream);
		int lineNum = 0;
		int ticksPerQuarterNote = 4;
		
		while (in.hasNextLine()) {
			lineNum++;
			String line = in.nextLine();
			
			// Skip comment lines
			if (line.startsWith("//")) {
				continue;
			}
			
			String[] attributes = line.split("\t");
			if (attributes.length < 5) {
				// Error if fewer than 5 columns
				System.err.println("WARNING: Line type not found. Skipping line " + lineNum + ": " + line);
				continue;
			}
			
			int tick = Integer.parseInt(attributes[0]);
			int voice = Integer.parseInt(attributes[4]);
			
			lastTick = Math.max(tick, lastTick);
			firstTick = Math.min(tick, firstTick);
			
			// Switch for different types of lines
			switch (attributes[5]) {
				// Attributes is the base line type describing time signature, tempo, etc.
				case "attributes":
					ticksPerQuarterNote = Integer.parseInt(attributes[6]);
					
					// Time signature
					int tsNumerator = Integer.parseInt(attributes[9]);
					int tsDenominator = Integer.parseInt(attributes[10]);
					
					int beatsPerBar = tsNumerator;
					int subBeatsPerBeat = 2;
					
					int subBeatsPerQuarterNote = tsDenominator / 2;
					
					// Check for compound meter
					if (beatsPerBar % 3 == 0 && beatsPerBar > 3) {
						beatsPerBar /= 3;
						subBeatsPerBeat = 3;
						
						subBeatsPerQuarterNote = tsDenominator / 4;
					}
					
					int tatumsPerSubBeat = ticksPerQuarterNote / subBeatsPerQuarterNote;
					
					Hierarchy newHierarchy = new Hierarchy(beatsPerBar, subBeatsPerBeat, tatumsPerSubBeat, hierarchy == null ? tick : 0);
					if (hierarchy != null && (hierarchy.beatsPerBar != newHierarchy.beatsPerBar ||
							hierarchy.subBeatsPerBeat != newHierarchy.subBeatsPerBeat ||
							hierarchy.tatumsPerSubBeat != newHierarchy.tatumsPerSubBeat)) {
						// TODO: Handle time changes
						System.err.println("WARNING: Meter change detected (" + hierarchy + " to " + newHierarchy + ") on line " + lineNum + ": " + line);
					} else {
						hierarchy = newHierarchy;
					}
					
					// Key signature
					int keyFifths = Integer.parseInt(attributes[7]);
					String keyMode = attributes[8];
					
					keys.add(new Key(((7 * keyFifths) + 144) % 12, keyMode.equalsIgnoreCase("Major"), getTimeFromTick(tick)));
					
					break;
					
				case "rest":
					// Do nothing
					break;
					
				case "chord":
					// There are notes here
					int duration = Integer.parseInt(attributes[6]);
					lastTick = Math.max(tick + duration, lastTick);
					
					int tieInfo = Integer.parseInt(attributes[7]);
					int numNotes = Integer.parseInt(attributes[8]);
					
					// Get all of the pitches
					int[] pitches = new int[numNotes];
					for (int i = 0; i < numNotes; i++) {
						try {
							pitches[i] = getPitchFromString(attributes[9 + i]);
						} catch (IOException e) {
							System.err.println("WARNING: " + e.getMessage() + " Skipping line " + lineNum + ": " + line);
							continue;
						}
					}
					
					// Handle each pitch
					for (int pitch : pitches) {
						switch (tieInfo) {
							// No tie
							case 0:
								notes.add(new Note(pitch, getTimeFromTick(tick), getTimeFromTick(tick), getTimeFromTick(tick + duration), voice));
								break;
								
							// Tie out
							case 1:
								unfinishedNotes.add(new Note(pitch, getTimeFromTick(tick), getTimeFromTick(tick), getTimeFromTick(tick + duration), voice));
								break;
								
							// Tie in
							case 2:
								try {
									Note matchedNote = findAndRemoveUnfinishedNote(pitch, getTimeFromTick(tick), voice);
									notes.add(new Note(pitch, matchedNote.onsetTime, matchedNote.valueOnsetTime, getTimeFromTick(tick), voice));
								} catch (IOException e) {
									System.err.println("WARNING: " + e.getMessage() + " Adding tied note as new note " + lineNum + ": " + line);
									notes.add(new Note(pitch, getTimeFromTick(tick), getTimeFromTick(tick), getTimeFromTick(tick + duration), voice));
								}
								break;
								
							// Tie in and out
							case 3:
								try {
									Note matchedNote = findAndRemoveUnfinishedNote(pitch, getTimeFromTick(tick), voice);
									unfinishedNotes.add(new Note(pitch, matchedNote.onsetTime, matchedNote.valueOnsetTime, getTimeFromTick(tick + duration), voice));
								} catch (IOException e) {
									System.err.println("WARNING: " + e.getMessage() + " Skipping note on line " + lineNum + ": " + line);
									unfinishedNotes.add(new Note(pitch, getTimeFromTick(tick), getTimeFromTick(tick), getTimeFromTick(tick + duration), voice));
								}
								break;
								
							// ???
							default:
								System.err.println("WARNING: Unknown tie type " + tieInfo + ". Skipping line " + lineNum + ": " + line);
								break;
						}
					}
					break;
					
				case "tremolo-m":
					duration = Integer.parseInt(attributes[6]);
					lastTick = Math.max(tick + duration, lastTick);
					
					numNotes = Integer.parseInt(attributes[8]);
					
					pitches = new int[numNotes];
					for (int i = 0; i < numNotes; i++) {
						try {
							pitches[i] = getPitchFromString(attributes[9 + i]);
						} catch (IOException e) {
							System.err.println("WARNING: " + e.getMessage() + " Skipping line " + lineNum + ": " + line);
							continue;
						}
					}
					
					for (int pitch : pitches) {
						// TODO: Decide how many notes to add here. i.e., default to eighth notes for now?
						int ticksPerTremolo = ticksPerQuarterNote / 2;
						int numTremolos = duration / ticksPerTremolo;
						
						
						for (int i = 0; i < numTremolos; i++) {
							notes.add(new Note(pitch, getTimeFromTick(tick + ticksPerTremolo * i), getTimeFromTick(tick + ticksPerTremolo * i), getTimeFromTick(tick + ticksPerTremolo * (i + 1)), voice));
						}
					}
					break;
					
				default:
					System.err.println("WARNING: Unrecognized line type. Skipping line " + lineNum + ": " + line);
					continue;
			}
		}
		
		in.close();
		
		// Check for any unfinished notes (because of ties out).
		for (Note note : unfinishedNotes) {
			System.err.println("WARNING: Tie never ended for note " + note + ". Adding note as untied.");
			notes.add(note);
		}
	}
	
	/**
	 * Find a tied out note that matches a new tied in note, return it, and remove it from
	 * {@link #unfinishedNotes}.
	 * 
	 * @param pitch The pitch of the tie.
	 * @param valueOnsetTime The onset time of the tied in note.
	 * @param voice The voice of the tied in note.
	 * 
	 * @return The note from {@link #unfinishedNotes} that matches the pitch onset time and voice.
	 * @throws IOException If no matching note is found.
	 */
	private Note findAndRemoveUnfinishedNote(int pitch, int valueOnsetTime, int voice) throws IOException {
		Iterator<Note> noteIterator = unfinishedNotes.iterator();
		while (noteIterator.hasNext()) {
			Note note = noteIterator.next();
			
			if (note.pitch == pitch && note.valueOffsetTime == valueOnsetTime && note.voice == voice) {
				noteIterator.remove();
				return note;
			}
		}
		
		throw new IOException("Tied note not found at pitch=" + pitch + " offset=" + valueOnsetTime + " voice=" + voice + ".");
	}
	
	/**
	 * Convert from XML tick to time, using 600ms per beat.
	 * 
	 * @param tick The XML tick.
	 * @return The time, in milliseconds.
	 */
	private int getTimeFromTick(int tick) {
		return (int) Math.round(((double) tick) / hierarchy.tatumsPerSubBeat / hierarchy.subBeatsPerBeat * 600);
	}
	
	/**
	 * Create and return a list of tatums based on the parsed {@link #hierarchy}, {@link #firstTick}, and
	 * {@link #lastTick}.
	 * 
	 * @return A list of the parsed tatums.
	 */
	private List<Tatum> getTatums() {
		List<Tatum> tatums = new ArrayList<Tatum>(lastTick - firstTick);
		
		for (int tick = firstTick; tick < lastTick; tick++) {
			tatums.add(new Tatum(getTimeFromTick(tick)));
		}
		
		return tatums;
	}
	
	/**
	 * Return a String version of the parsed musical score into our mv2h format.
	 * 
	 * @return The parsed musical score.
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		
		for (Note note : notes) {
			sb.append(note).append('\n');
		}
		
		for (Tatum tatum : getTatums()) {
			sb.append(tatum).append('\n');
		}
		
		for (Key key : keys) {
			sb.append(key).append('\n');
		}
		
		sb.append(hierarchy).append('\n');
		
		return sb.toString();
	}
	
	/**
	 * Get the pitch number of a note given its String.
	 * 
	 * @param pitchString A pitch String, like C##4, or Ab1, or G7.
	 * @return The number of the given pitch, with A4 = 440Hz = 69.
	 * 
	 * @throws IOException If a parse error occurs.
	 */
	private static int getPitchFromString(String pitchString) throws IOException {
		char pitchChar = pitchString.charAt(0);
		int pitch;
		switch (pitchChar) {
			case 'C':
				pitch = 0;
				break;
				
			case 'D':
				pitch = 2;
				break;
				
			case 'E':
				pitch = 4;
				break;
				
			case 'F':
				pitch = 5;
				break;
				
			case 'G':
				pitch = 7;
				break;
				
			case 'A':
				pitch = 9;
				break;
				
			case 'B':
				pitch = 11;
				break;
				
			default:
				throw new IOException("Pith " + pitchChar + " not recognized.");
		}
		
		int accidental = pitchString.length() - pitchString.replace("#", "").length();
		accidental -= pitchString.length() - pitchString.replace("b", "").length();
		
		int octave = Integer.parseInt(pitchString.substring(1).replace("#", "").replace("b", ""));						
		
		return (octave + 1) * 12 + pitch + accidental;
	}
}