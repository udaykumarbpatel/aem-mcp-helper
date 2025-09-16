(function (document) {
  'use strict';

  function isBlank(value) {
    return !value || !value.trim();
  }

  function updateResponse(target, text) {
    if (!target) {
      return;
    }
    target.textContent = text || '';
  }

  function toggleLoading(root, button, isLoading) {
    if (button) {
      button.disabled = !!isLoading;
    }
    if (root) {
      root.classList.toggle('chat-component--loading', !!isLoading);
    }
  }

  function sendPrompt(root) {
    var endpoint = root.dataset.chatEndpoint;
    var button = root.querySelector('.chat-component__button');
    var input = root.querySelector('.chat-component__prompt');
    var responseTarget = root.querySelector('.chat-component__response');

    if (!endpoint || !button || !input || !responseTarget) {
      return;
    }

    button.addEventListener('click', function () {
      var prompt = (input.value || '').trim();
      if (isBlank(prompt)) {
        updateResponse(responseTarget, 'Please enter a prompt before sending.');
        return;
      }

      toggleLoading(root, button, true);
      updateResponse(responseTarget, 'Contacting assistant…');

      fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ prompt: prompt })
      })
        .then(function (res) {
          if (!res.ok) {
            throw new Error('Request failed with status ' + res.status);
          }
          return res.json();
        })
        .then(function (data) {
          if (data && data.response) {
            updateResponse(responseTarget, data.response);
          } else if (data && data.error) {
            updateResponse(responseTarget, data.error);
          } else {
            updateResponse(responseTarget, 'No response returned from the service.');
          }
        })
        .catch(function (error) {
          updateResponse(responseTarget, 'Unable to contact the chat service: ' + error.message);
        })
        .finally(function () {
          toggleLoading(root, button, false);
        });
    });
  }

  function init() {
    var components = document.querySelectorAll('.chat-component');
    components.forEach(function (component) {
      if (!component.dataset.chatInitialised) {
        component.dataset.chatInitialised = 'true';
        sendPrompt(component);
      }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})(document);
