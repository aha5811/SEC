package aha5811.sec;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

public class SlackExportsConverter {
	
	private final static String STYLE_FILENAME = "style.css";
	private final static String JQUERY_FILENAME = "jquery.3.7.0.min.js";
	private final static String SCRIPT_FILENAME = "script.js";
	private final static String INDEX = "index.html";
	
	private static enum S {
		IN_ZIPS, IN_CH, IN_U, IN_MSG_JSON, IN_MSGS, FILES, FILES_NEW, OUT_CH, OUT_MSGS
	}
	
	private final Map<S, Integer> stats = new HashMap<>();
	private final Collection<String> inErrors = new LinkedList<>();
	private final Collection<String> outErrors = new LinkedList<>();
	
	private final String slackId;
	private final Date now;
	private final String uncache;
	private final String generated;
	
	/* user id -> user map */
	private final Map<String, Map<String, Object>> i2u = new HashMap<>();
	
	/* channel id -> channel map */
	private final Map<String, Map<String, Object>> i2c = new HashMap<>();

	/* channel name -> timestamp -> message */
	private final Map<String, Map<Long, Map<String, Object>>> c2i2msg = new HashMap<>();

	private SlackExportsConverter(final String id) {
		this.slackId = id;
		
		this.now = new Date();
		this.uncache = "?v=ts" + this.now.getTime();
		this.generated = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault()).format(this.now);
	}

	private final static FilenameFilter zips = new FilenameFilter() {
		@Override
		public boolean accept(final File dir, final String name) {
			return name.endsWith(".zip");
		}
	};
	
	private final static Map<String, String> fsEnv = Collections.singletonMap("create", "false");
	
	public static void main(final String[] args) {
		
		String id = null;
		File inDir = null;
		File exDir = null;
		{
			if (args.length < 2) {
				System.err.println(
						"args: slack-id (the xxx part in your xxx.slack.com)"
								+ " input-directory (with all export zip files)"
								+ " export-directory (optional, where the export should go to)"
						);
				return;
			}
			
			id = args[0];
			
			final String inName = args[1];
			inDir = new File(inName);
			if (!inDir.exists() || !inDir.isDirectory()) {
				System.err.println("no dir found with '" + inName + "'!");
				return;
			}
			if (inDir.list(zips).length == 0) {
				System.err.println("'" + inName + "' contains no .zip files!");
				return;
			}
			
			if (args.length == 3) {
				final String exName = args[2];
				exDir = new File(exName);
				if (!checkDir(exDir, "given export"))
					return;
				else if (!createDir(exDir))
					return;
			} else {
				exDir = new File(inDir.getParentFile(), inDir.getName() + "_export");
				if (!checkDir(exDir, "default export"))
					return;
				else if (!createDir(exDir))
					return;
			}
		}
		
		System.out.println(
				"SlackExportsConverter will import data for '" + id + "'"
						+ " from '" + inDir + "' and export to '" + exDir + "'"
				);
		
		final long start = System.currentTimeMillis();
		
		final SlackExportsConverter sec = new SlackExportsConverter(id);
		
		sec.importFrom(inDir);
		
		final long inDone = System.currentTimeMillis();

		System.out.println(
				" * imported"
						+ " " + sec.stats.get(S.IN_U) + " users"
						+ ", " + sec.stats.get(S.IN_CH) + " channels"
						+ ", " + sec.stats.get(S.IN_MSGS) + " messages from " + sec.stats.get(S.IN_MSG_JSON) + " message files"
						+ " from " + sec.stats.get(S.IN_ZIPS) + " zip files"
						+ " in " + (inDone - start) + "ms"
				);
		outErrors(sec.inErrors);
		
		sec.exportTo(exDir);

		final long exDone = System.currentTimeMillis();
		
		System.out.println(
				" * exported"
						+ " " + sec.stats.get(S.OUT_MSGS) + " unique messages"
						+ " with " + sec.stats.get(S.FILES) + " attachements"
						+ " (" + sec.stats.get(S.FILES_NEW) + " new ones were downloaded)"
						+ " from " + sec.i2u.size() + " unique users"
						+ " in " + sec.stats.get(S.OUT_CH) + " unique channels"
						+ " in " + (exDone - inDone) + "ms"
				);
		outErrors(sec.outErrors);

		System.out.println("done.");
	}
	
	private static boolean checkDir(final File dir, final String what) {
		if (dir.exists() && !dir.isDirectory()) {
			System.err.println("file (instead of directory) found at " + what + " location '" + dir + "'!");
			return false;
		}
		return true;
	}

	private static boolean createDir(final File dir) {
		if (!dir.exists()) {
			dir.mkdir();
			if (!dir.exists()) {
				System.err.println("could not create '" + dir + "'!");
				return false;
			}
		}
		return true;
	}
	
	private static void outErrors(final Collection<String> errors) {
		if (!errors.isEmpty()) {
			System.out.println("but there were " + errors.size() + " error(s):");
			for (final String e : errors)
				System.out.println("- " + e);
		}
	}

	private void importFrom(final File inDir) {
		for (final File f : inDir.listFiles(zips))
			processZip(f);
	}
	
	private void processZip(final File zip) {
		try {
			final URI uri = new URI("jar:" + zip.toURI().toString());
			final FileSystem zipFS = FileSystems.newFileSystem(uri, fsEnv);
			Files.list(zipFS.getPath("/")).forEach(new Consumer<Path>() {
				@Override
				public void accept(final Path p) {
					if (Files.isDirectory(p))
						try {
							Files.list(p).forEach(new Consumer<Path>() {
								@Override
								public void accept(final Path pp) {
									if (pp.toString().endsWith(".json"))
										readChannelMsgs(SlackExportsConverter.this.c2i2msg, p, pp);
								}
							});
						} catch (final IOException e) {
							SlackExportsConverter.this.inErrors.add("could not access '" + p + "' in '" + zip + "'");
						}
					else if (p.endsWith("/users.json"))
						readObjects(SlackExportsConverter.this.i2u, p, S.IN_U);
					else if (p.endsWith("/channels.json"))
						readObjects(SlackExportsConverter.this.i2c, p, S.IN_CH);
				}
			});
			spp(S.IN_ZIPS);
			zipFS.close();
		} catch (final IOException e) {
			this.inErrors.add("could not access '" + zip + "' as zip file");
		} catch (final URISyntaxException e) {
			this.inErrors.add("could not work with '" + zip + "'");
		}
	}
	
	private void readChannelMsgs(final Map<String, Map<Long, Map<String, Object>>> c2i2msg, final Path channelP,
			final Path jsonP) {
		
		String channel = channelP.toString();
		if (channel.startsWith("/"))
			channel = channel.substring(1);
		if (channel.endsWith("/"))
			channel = channel.substring(0, channel.length() - 1);
		
		if (!c2i2msg.containsKey(channel))
			c2i2msg.put(channel, new HashMap<>());
		
		final Map<Long, Map<String, Object>> i2msg = c2i2msg.get(channel);
		
		try {
			@SuppressWarnings("unchecked")
			final Collection<Map<String, Object>> os = new Gson().fromJson(readFile(jsonP), Collection.class);
			for (final Map<String, Object> o : os) {
				final String ts = (String) o.get("ts");
				final long i = Long.parseLong(ts.replace(".", ""));
				i2msg.put(i, o);
				spp(S.IN_MSGS);
			}
			spp(S.IN_MSG_JSON);
		} catch (JsonSyntaxException | JsonIOException e) {
			this.inErrors.add("could not process '" + jsonP + "'");
		}
	}

	private void readObjects(final Map<String, Map<String, Object>> m, final Path p, final S stat) {
		try {
			@SuppressWarnings("unchecked")
			final Collection<Map<String, Object>> os = new Gson().fromJson(readFile(p), Collection.class);
			for (final Map<String, Object> o : os) {
				m.put((String) o.get("id"), o);
				spp(stat);
			}
		} catch (JsonSyntaxException | JsonIOException e) {
			this.inErrors.add("could not process '" + p + "'");
		}
	}
	
	private String readFile(final Path p) {
		final StringBuilder bob = new StringBuilder();
		try (Stream<String> lines = Files.lines(p, StandardCharsets.UTF_8)) {
			lines.forEach(s -> bob.append(s).append("\n"));
		} catch (final IOException e) {
			this.inErrors.add("could not read '" + p + "'");
		}
		return bob.toString();
	}

	private void exportTo(final File toDir) {
		final StringBuffer index = new StringBuffer();

		appendHeader(index, null, 0, "main");
		appendTitle(index, "Channels in <span>" + this.slackId + "</span>");
		index.append("<ul>");

		final List<String> cids = new LinkedList<>(this.i2c.keySet());
		Collections.sort(cids, new Comparator<String>() {
			@Override
			public int compare(final String cid1, final String cid2) {
				final boolean isA1 = isArchivedC(cid1), isA2 = isArchivedC(cid2);
				if (isA1 && !isA2)
					return 1;
				else if (!isA1 && isA2)
					return -1;
				final String cname1 = (String) SlackExportsConverter.this.i2c.get(cid1).get("name"),
						cname2 = (String) SlackExportsConverter.this.i2c.get(cid2).get("name");
				return cname1.compareTo(cname2);
			}
		});

		for (final String cid : cids) {
			final String cname = (String) this.i2c.get(cid).get("name");

			{
				index.append("<li><a href=\"" + cname + "/" + INDEX + "\">" + cname + "</a>");
				if (isArchivedC(cid))
					index.append(" [archiviert]");
				index.append("</li>");
			}

			final Collection<Map<String, Object>> msgs = new LinkedList<>();
			{
				final List<Long> mids = new LinkedList<>(this.c2i2msg.get(cname).keySet());
				Collections.sort(mids);
				for (final Long mid : mids)
					msgs.add(this.c2i2msg.get(cname).get(mid));
			}

			final File dir = new File(toDir, cname);
			dir.mkdir();

			final StringBuffer buffy = processMsgs(msgs, dir);

			writeB2F(buffy, new File(dir, INDEX));
			spp(S.OUT_CH);
		}
		
		index.append("</ul>");
		appendFooter(index);
		
		writeB2F(index, new File(toDir, INDEX));
		
		writeFile2File(toDir, STYLE_FILENAME);
		writeFile2File(toDir, JQUERY_FILENAME);
		writeFile2File(toDir, SCRIPT_FILENAME);
	}
	
	private boolean isArchivedC(final String cid) {
		return Boolean.TRUE.equals(this.i2c.get(cid).get("is_archived"));
	}

	private void appendHeader(final StringBuffer buffy, final String title, final int depth, final String bodyClass) {
		String trav = "";
		{
			int i = 0;
			while (i++ < depth)
				trav += "../";
		}
		buffy
		.append("<!doctype html>")
		.append("<html>")
		.append("<head>")
		.append("<meta charset=\"ISO-8859-1\"/>")
		.append("<meta name=\"generator\" content=\"aha.misc.SlackExportsConverter\"/>")
		.append("<meta name=\"date\" content=\"" + this.generated + "\">")
		.append("<title>" + this.slackId + (title == null ? "" : " / " + title) + "</title>")
		.append("<link rel=\"stylesheet\" type=\"text/css\" href=\"" + trav + STYLE_FILENAME + this.uncache + "\">")
		.append("<script type=\"text/javascript\" src=\"" + trav + JQUERY_FILENAME + "\"></script>")
		.append("<script type=\"text/javascript\" src=\"" + trav + SCRIPT_FILENAME + this.uncache + "\"></script>")
		.append("</head>")
		.append("<body" + (bodyClass == null ? "" : " class=\"" + bodyClass + "\"") + ">")
		;
	}
	
	private static void appendTitle(final StringBuffer buffy, final String title) {
		buffy.append("<div class=\"title\">" + title + "</div>");
	}

	private static void appendFooter(final StringBuffer buffy) {
		buffy
		.append("</body>")
		.append("</html>")
		;
	}

	private void writeFile2File(final File toDir, final String filename) {
		final StringBuffer buffy = new StringBuffer();
		try {
			final BufferedReader br = new BufferedReader(
					new InputStreamReader(
							SlackExportsConverter.class.getResourceAsStream(filename)
							)
					);
			String line;
			while ((line = br.readLine()) != null)
				buffy.append(line).append(System.getProperty("line.separator"));
			br.close();
		} catch (final IOException e) {
			this.outErrors.add("could not write '" + filename + "'");
		}

		writeB2F(buffy, new File(toDir, filename));
	}

	private void writeB2F(final StringBuffer buffy, final File outF) {
		try {
			final BufferedWriter out = new BufferedWriter(new FileWriter(outF));
			out.write(buffy.toString());
			out.flush();
			out.close();
		} catch (final IOException e) {
			this.outErrors.add("could not write file '" + outF + "'");
		}
	}

	private StringBuffer processMsgs(final Collection<Map<String, Object>> msgs, final File dir) {
		final StringBuffer buffy = new StringBuffer();
		appendHeader(buffy, dir.getName(), 1, null);
		appendLinks(buffy);
		appendTitle(buffy, "Channel <span>" + dir.getName() + "</span>");
		buffy.append("<ul>");
		
		for (final Map<String, Object> msg : msgs) {
			final Map<String, File> i2f = new HashMap<>();
			@SuppressWarnings({ "unchecked", "rawtypes" })
			final Collection<Map<String, String>> files = (Collection) msg.get("files");
			if (files != null && !files.isEmpty()) {
				final String date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(toDate((String) msg.get("ts")));
				for (final Map<String, String> file : files) {
					final String id = file.get("id"),
							name = file.get("name"),
							url = file.get("url_private_download");
					if (url != null) { // deleted file
						final File out = new File(dir, date + "_" + id + "_" + name);
						dl(url, out);
						i2f.put(id, out);
					}
				}
			}
			appendMsg(buffy, msg, i2f, files);
			spp(S.OUT_MSGS);
		}
		
		buffy.append("</ul>");
		appendFooter(buffy);
		return buffy;
	}
	
	private void appendLinks(final StringBuffer buffy) {
		buffy
		.append("<a class=\"back\" href=\"../" + INDEX + "\">&#11014;</a>")
		.append("<a class=\"files\">&#128450;</a>")
		.append("<a class=\"imgs\">&#128444;</a>")
		;
	}
	
	private void dl(final String url, final File out) {
		spp(S.FILES);
		if (out.exists())
			return;
		try {
			final ReadableByteChannel dlCh = Channels.newChannel(new URL(url).openStream());
			final FileOutputStream fos = new FileOutputStream(out);
			fos.getChannel().transferFrom(dlCh, 0, Long.MAX_VALUE);
			fos.flush();
			fos.close();
			spp(S.FILES_NEW);
		} catch (final IOException e) {
			this.outErrors.add("could not download '" + url + "'");
		}
	}

	private static Date toDate(final String ts) {
		final String t = ts.substring(0, ts.indexOf("."));
		return new Date(Long.parseLong(t) * 1000);
	}

	private void appendMsg(final StringBuffer buffy, final Map<String, Object> msg, final Map<String, File> i2f,
			final Collection<Map<String, String>> files) {
		buffy.append("<li>");
		appendTime(buffy, (String) msg.get("ts"));
		appendUser(buffy, this.i2u.get(msg.get("user")));
		appendContent(buffy, (String) msg.get("text"), i2f);
		appendFiles(buffy, i2f, files);
		buffy.append("</li>");
	}

	private static void appendTime(final StringBuffer buffy, final String timestamp) {
		final String date = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(toDate(timestamp));
		buffy.append("<span name=\"p" + timestamp.replace(".", "") + "\" class=\"date\">" + date + "</span>");
	}

	private static void appendUser(final StringBuffer buffy, final Map<String, Object> u) {
		buffy.append("<span class=\"user\">" + getUName(u) + "</span>");
	}
	
	private static String getUName(final Map<String, Object> u) {
		@SuppressWarnings({ "unchecked", "rawtypes" })
		final Map<String, Object> p = (Map) u.get("profile");
		String name = (String) p.get("display_name");
		if (name.isEmpty())
			name = (String) p.get("real_name");
		return name;
	}
	
	private void appendContent(final StringBuffer buffy, final String s, final Map<String, File> i2f) {
		
		final Collection<String> usedFids = new HashSet<>();

		final StringBuffer willow = new StringBuffer();
		final Matcher m = Pattern.compile("<.*?>").matcher(s);
		int lastStart = 0;
		while (m.find()) {
			willow.append(s.substring(lastStart, m.start()));
			willow.append(processLink(m.group(), i2f, usedFids));
			lastStart = m.end();
		}
		willow.append(s.substring(lastStart));
		
		for (final String fid : usedFids)
			i2f.remove(fid);
		
		buffy.append("<div class=\"msg\">" + willow.toString() + "</div>");
		
	}

	private String processLink(final String s, final Map<String, File> i2f, final Collection<String> usedFids) {
		
		String l = s.substring(1, s.length() - 1),
				l1 = l.substring(1),
				lname = null;
		final int split = l.indexOf("|");
		if (split != -1) {
			lname = l.substring(split + 1);
			l1 = l.substring(1, split);
			l = l.substring(0, split);
		}

		if (l.startsWith("http://") || l.startsWith("https://")) {
			String link = l, linkText = lname == null ? l : lname, arr = "&nearr;";
			boolean newW = true;
			if (l.startsWith("https://" + this.slackId + ".slack.com/files/")) {
				final int last = l.lastIndexOf("/");
				link = link.substring(0, last);
				linkText = l.substring(last + 1);
				final String id = link.substring(link.lastIndexOf("/") + 1);
				usedFids.add(id);
				arr = "&darr;";
				return filelink(i2f.get(id), linkText);
			} else if (l.startsWith("https://" + this.slackId + ".slack.com/archives/")) {
				final int last = l.lastIndexOf("/");
				link = link.substring(0, last);
				final String msgId = l.substring(last + 1);
				final String cId = link.substring(link.lastIndexOf("/") + 1);
				final String cName = (String) this.i2c.get(cId).get("name");
				link = "../" + cName + "/index.html#" + msgId;
				arr = "&rarr;";
				linkText = "Beitrag in " + cName;
				newW = false;
			}
			return "<span class=\"link\">" + arr + "<a href=\"" + link + "\"" + (newW ? " target=\"_blank\"" : "") + ">"
			+ linkText + "</a></span>";
		} else if (l.equals("!channel"))
			return "(@Channel)";
		else if (l.startsWith("@") && this.i2u.containsKey(l1))
			return "(@" + getUName(this.i2u.get(l1)) + ")";
		else if (l.startsWith("#"))
			return "(Channel \"" + lname + "\")";
		else if (l.startsWith("tel:") || l.startsWith("mailto:"))
			return lname;
		else if (l.equals("!everyone"))
			return "(@alle)";
		else
			return l;
	}
	
	private void appendFiles(final StringBuffer buffy, final Map<String, File> i2f,
			final Collection<Map<String, String>> files) {
		if (i2f.isEmpty())
			return;
		buffy.append("<span class=\"files\">");
		for (final Map<String, String> f : files) {
			final String fid = f.get("id");
			if (i2f.containsKey(fid))
				buffy.append(filelink(i2f.get(fid), f.get("name")));
		}
		buffy.append("</span>");
	}

	private static String filelink(final File f, final String linkText) {
		try {
			String link = URLEncoder.encode(f.getName(), "UTF-8");
			link = link.replace("+", "%20");
			return "<span class=\"link file\">&darr;<a href=\"" + link + "\" target=\"_blank\">" + linkText + "</a></span>";
		} catch (final UnsupportedEncodingException ex) {
			return null; /* won't happen */
		}
	}

	private void spp(final S s) {
		inc(this.stats, s);
	}
	
	private static <K> void inc(final Map<K, Integer> m, final K k) {
		if (m.get(k) == null)
			m.put(k, 0);
		m.put(k, m.get(k) + 1);
	}
	
}
