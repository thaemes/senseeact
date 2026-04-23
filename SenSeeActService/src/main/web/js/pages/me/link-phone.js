class LinkPhonePage {
	/**
	 * Properties:
	 * - _user (SenSeeAct user object)
	 * - _onsIdEdit (TextEdit)
	 * - _projectEdit (TextEdit)
	 * - _submitButton (jQuery element)
	 * - _alreadyMessage (jQuery element)
	 * - _error (jQuery element)
	 * - _wait (jQuery element)
	 * - _result (jQuery element)
	 * - _qrImg (jQuery element)
	 * - _ssaIdValue (jQuery element)
	 * - _emailValue (jQuery element)
	 * - _onsInstance (string)
	 */
	constructor() {
		this._user = null;
		this._onsIdEdit = null;
		this._projectEdit = null;
		this._submitButton = $('#link-phone-submit');
		this._alreadyMessage = $('#link-phone-already');
		this._error = $('#link-phone-error');
		this._wait = $('#link-phone-wait');
		this._result = $('#link-phone-result');
		this._qrImg = $('#link-phone-qr-img');
		this._ssaIdValue = $('#link-phone-ssa-id');
		this._emailValue = $('#link-phone-email');
		this._onsInstance = null;
		this._requestRunning = false;
		this._generatedOnce = false;
		var self = this;
		checkLogin(function(data) {
			self._onGetUserDone(data);
		});
	}

	_onGetUserDone(data) {
		this._user = data;
		this._createView();
		this._applyUrlParams();
	}

	_createView() {
		let background = new BackgroundImage($('#background-image'), true);
		background.render();

		let header = new PageBackHeader($('.page-back-header'));
		header.title = i18next.t('link_phone_title');
		header.backUrl = basePath + '/me';
		header.render();

		this._onsIdEdit = new TextEdit();
		this._onsIdEdit.id = 'link-phone-ons-id-input';
		this._onsIdEdit.placeholder = i18next.t('link_phone_ons_id_placeholder');
		this._onsIdEdit.setInputTypeNonNegInt();
		this._onsIdEdit.oninput(() => {
			this._resetAlreadyLinkedState();
		});
		$('#link-phone-ons-id').append(this._onsIdEdit.element);

		this._projectEdit = new TextEdit();
		this._projectEdit.id = 'link-phone-project-code-input';
		this._projectEdit.placeholder =
			i18next.t('link_phone_project_code_placeholder');
		this._projectEdit.oninput(() => {
			this._resetAlreadyLinkedState();
		});
		$('#link-phone-project-code').append(this._projectEdit.element);

		this._error.hide();
		this._alreadyMessage.hide();
		this._submitButton.show();
		this._wait.hide();
		this._result.hide();
		this._ssaIdValue.text('');
		this._emailValue.text('');

		var self = this;
		animator.addAnimatedClickHandler(this._submitButton,
			this._submitButton,
			'animate-blue-button-click',
			function(clickId) {
				self._onGenerateClick(clickId);
			},
			function(result) {
				self._onGenerateCompleted(result);
			}
		);

		menuController.showSidebar();
		menuController.selectMenuItem('me-link-phone');
		$(document.body).addClass('tinted-background');
		let content = $('#content');
		content.addClass('white-background');
		content.css('visibility', 'visible');
	}

	_applyUrlParams() {
		let params = parseURL(window.location.href).params;
		if (params['onsId'])
			this._onsIdEdit.input.val(params['onsId']);
		if (params['project'])
			this._projectEdit.input.val(params['project']);
		if (params['onsInstance']) {
			let onsInstance = (params['onsInstance'] + '').trim();
			if (onsInstance.length > 0)
				this._onsInstance = onsInstance;
		}
	}

	_onGenerateClick(clickId) {
		if (this._requestRunning) {
			animator.onAnimatedClickHandlerCompleted(clickId, {
				success: false,
				error: 'unexpected_error'
			});
			return;
		}
		this._error.hide();
		this._alreadyMessage.hide();
		this._submitButton.show();
		this._result.hide();
		this._ssaIdValue.text('');
		this._emailValue.text('');
		this._qrImg.removeAttr('src');

		let onsId = this._onsIdEdit.readInt(1, null, false, true);
		if (onsId === false) {
			animator.onAnimatedClickHandlerCompleted(clickId, {
				success: false,
				error: 'link_phone_invalid_ons_id'
			});
			return;
		}

		this._setLoading(true);
		this._requestRunning = true;
		let project = this._projectEdit.input.val().trim();
		this._checkExistingLinkAndSignup(clickId, onsId, project);
	}

	_onGenerateDone(clickId, result) {
		animator.onAnimatedClickHandlerCompleted(clickId, {
			success: true,
			result: result
		});
	}

	_onGenerateFail(clickId, xhr) {
		let client = new SenSeeActClient();
		let error = 'unexpected_error';
		if (client.hasInvalidInputField(xhr, 'onsId')) {
			error = 'link_phone_invalid_ons_id';
		} else if (xhr.status == 403) {
			error = 'link_phone_forbidden';
		}
		animator.onAnimatedClickHandlerCompleted(clickId, {
			success: false,
			error: error
		});
	}

	_onGenerateCompleted(result) {
		this._setLoading(false);
		this._requestRunning = false;
		if (result.success) {
			this._showResult(result.result);
			return;
		}
		if (result.error == 'unexpected_error') {
			showToast(i18next.t('unexpected_error'));
			return;
		}
		this._error.text(i18next.t(result.error));
		this._error.show();
	}

	_checkExistingLinkAndSignup(clickId, onsId, project) {
		let url = servicePath + '/user/detox/ons-lookup?onsId=' +
			encodeURIComponent(onsId);
		if (this._onsInstance)
			url += '&onsInstance=' + encodeURIComponent(this._onsInstance);
		var self = this;
		$.ajax({
			method: 'GET',
			url: url
		})
		.done(function() {
			self._showAlreadyLinkedNote();
			self._runSignup(clickId, onsId, project);
		})
		.fail(function(xhr, status, error) {
			let client = new SenSeeActClient();
			if (client.hasInvalidInputField(xhr, 'onsId')) {
				animator.onAnimatedClickHandlerCompleted(clickId, {
					success: false,
					error: 'link_phone_invalid_ons_id'
				});
				return;
			}
			if (xhr.status != 404) {
				showToast(i18next.t('unexpected_error'));
			}
			self._runSignup(clickId, onsId, project);
		});
	}

	_resetAlreadyLinkedState() {
		if (this._alreadyMessage.is(':visible'))
			this._alreadyMessage.hide();
		if (this._generatedOnce) {
			this._generatedOnce = false;
			this._submitButton.prop('disabled', false);
		}
	}

	_buildOnsSignupUrl(onsId, project) {
		let url = servicePath + '/user/detox/ons-signup?onsId=' +
			encodeURIComponent(onsId);
		if (this._onsInstance)
			url += '&onsInstance=' + encodeURIComponent(this._onsInstance);
		if (project.length > 0)
			url += '&project=' + encodeURIComponent(project);
		return url;
	}

	_showAlreadyLinkedNote() {
		this._alreadyMessage.text(i18next.t('link_phone_already_linked'));
		this._alreadyMessage.show();
	}

	_runSignup(clickId, onsId, project) {
		let url = this._buildOnsSignupUrl(onsId, project);
		var self = this;
		$.ajax({
			method: 'POST',
			url: url
		})
		.done(function(result) {
			self._onGenerateDone(clickId, result);
		})
		.fail(function(xhr, status, error) {
			self._onGenerateFail(clickId, xhr);
		});
	}

	_showResult(result) {
		if (!result || !result.qrPngBase64) {
			this._error.text(i18next.t('link_phone_no_qr'));
			this._error.show();
			return;
		}
		this._qrImg.attr('src', 'data:image/png;base64,' +
			result.qrPngBase64);
		this._ssaIdValue.text(result.ssaId || '');
		this._emailValue.text(result.email || '');
		this._result.show();
		this._generatedOnce = true;
		this._submitButton.prop('disabled', true);
	}

	_setLoading(loading) {
		this._submitButton.prop('disabled', loading);
		if (loading)
			this._wait.show();
		else
			this._wait.hide();
	}
}

new LinkPhonePage();
