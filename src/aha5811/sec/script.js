
$(document).ready(function () {
	
	var f = $('a.files');
	if (f.length > 0) {
	
		if ($('span.link.file').length == 0)
			f.addClass('unavail');
		else
			f.click(function () {
				var hide = f.data('hide');
				if (hide == null)
					hide = true;
				
				if (hide) {
					$('body > ul > li')
					.each(
						function (_, li) {
							if ($('span.link.file', li).length == 0)
								$(li).hide();
						}
					);
				} else
					$('body > ul > li').show();
					
				f.data('hide', !hide);
				if (f.data('hide'))
					f.removeClass('active');
				else
					f.addClass('active');
			});
			
		var img = $('a.imgs');
		if (img.length > 0) {
			var avail = false;
			if (!f.hasClass('unavail')) {
				$('span.link.file').each(function (_, fl) {
					var suff = $('a', fl).attr('href').toLowerCase();
					suff = suff.substring(suff.lastIndexOf('.') + 1);
					if (isImg(suff))
						$(fl).addClass('imglink');
				});
				avail = $('.imglink').length > 0;
			}
			if (!avail)
				img.addClass('unavail');
			else
				img.click(function () {
					var imgs = img.data('imgs');
					if (imgs == null)
						imgs = true;
						
					if (imgs) {
						$('.imglink').each(function (_, fl) {
							var src = $('a', fl).attr('href');
							$('<a></a>').attr({ target: '_blank', href: src }).addClass('linkimg')
							.append($('<img></img>').attr({ src: src }))
							.insertAfter(fl);
							$(fl).hide();
						});
					} else {
						$('.imglink').show();
						$('.linkimg').remove();
					}
						
					img.data('imgs', !imgs);
					if (img.data('imgs'))
						img.removeClass('active');
					else
						img.addClass('active');
				});
		}
		
	}
	
	function isImg(suff) {
		return ['jpg', 'jpeg', 'png', 'bmp', 'webp'].indexOf(suff) != -1;
	}
	
});
