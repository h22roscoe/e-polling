(function() {
  let boxOptions = {
    id: id++,
    parentId: null,
    type: 'Issue'
  };

  new Box(boxOptions);

  list.push(boxOptions);

  $('#submit-poll').click(function() {
    for (let i = 0; i < list.length; i++) {
      let elem = list[i];
      elem.value = $('#box' + elem.id).find('input').val();
      let oldParent = elem.parentId;
      console.log(elem);
      console.log(oldParent);
      elem.parentId = oldParent ? parseInt(oldParent.substring(3)) : null;
    }

    let data = {
      email: $('#email').val(),
      password: $('#password').val(),
      name: list[0].value,
      list: list,
    };

    $.ajax({
      type: 'POST',
      url: '/create',
      data: JSON.stringify(data),
      dataType: 'json',
      success: function(data) {
        console.log(data);
        window.location.href = '/results/' + data;
      },
      error: function() {
        console.error('Problem submitting poll');
      }
    });
  });
})();
