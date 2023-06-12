# SEC
slack exports converter

usage:
* export slack data like this https://slack.com/help/articles/201658943-Export-your-workspace-data
* put all export.zips in one dir
* call `SlackExportsConverter slack-id (the xxx part in your xxx.slack.com) input-directory (with all export zip files) export-directory (optional, where the export should go to)`
* or use the SEC.jar in /export (contains gson-2.10.1.jar in /lib) and call `java -jar SEC.jar slack-id input-directory [export-directory]`

generates:

\ index.html with list of all channels  
&nbsp; script.js  
&nbsp; style.css  
for each channel:  
\ channel-name  
&nbsp; &nbsp;\ index.html with list of all messages  
&nbsp; &nbsp;\ all attachements of all messages with `{date of message}_{file id}_{file name}`

uses https://github.com/google/gson / https://search.maven.org/artifact/com.google.code.gson/gson  
you'll find gson-2.10.1.jar in /lib
