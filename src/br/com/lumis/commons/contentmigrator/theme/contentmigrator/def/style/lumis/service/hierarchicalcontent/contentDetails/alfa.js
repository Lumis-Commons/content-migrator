<%
	var rows = lum_xpath.getMaps("//data/row");
	var regex = /(<header[\s\S]*<\/header>)/i;
	for (var i in rows) {
		var row = rows[i];

			if (row.linkContent.match(regex)) {
				lum_out.print(new String(row.linkContent.match(regex)[0]));

%>
		<main id="main" class="main">
<%
		lum_out.print(new String(row.linkContent).replace(regex, ""));
%>
		</main>
<%
	} else {
%>
		<main id="main" class="main">
<%
		lum_out.print(row.linkContent)
%>
		</main>
<%
	}

	}
%>