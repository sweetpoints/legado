// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'library_database.dart';

// ignore_for_file: type=lint
class $StoredBooksTable extends StoredBooks
    with TableInfo<$StoredBooksTable, LibraryBookRow> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $StoredBooksTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _idMeta = const VerificationMeta('id');
  @override
  late final GeneratedColumn<String> id = GeneratedColumn<String>(
    'id',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _dataMeta = const VerificationMeta('data');
  @override
  late final GeneratedColumn<String> data = GeneratedColumn<String>(
    'data',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [id, data];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'books';
  @override
  VerificationContext validateIntegrity(
    Insertable<LibraryBookRow> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    } else if (isInserting) {
      context.missing(_idMeta);
    }
    if (data.containsKey('data')) {
      context.handle(
        _dataMeta,
        this.data.isAcceptableOrUnknown(data['data']!, _dataMeta),
      );
    } else if (isInserting) {
      context.missing(_dataMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  LibraryBookRow map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return LibraryBookRow(
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}id'],
      )!,
      data: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}data'],
      )!,
    );
  }

  @override
  $StoredBooksTable createAlias(String alias) {
    return $StoredBooksTable(attachedDatabase, alias);
  }
}

class LibraryBookRow extends DataClass implements Insertable<LibraryBookRow> {
  final String id;
  final String data;
  const LibraryBookRow({required this.id, required this.data});
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['id'] = Variable<String>(id);
    map['data'] = Variable<String>(data);
    return map;
  }

  StoredBooksCompanion toCompanion(bool nullToAbsent) {
    return StoredBooksCompanion(id: Value(id), data: Value(data));
  }

  factory LibraryBookRow.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return LibraryBookRow(
      id: serializer.fromJson<String>(json['id']),
      data: serializer.fromJson<String>(json['data']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<String>(id),
      'data': serializer.toJson<String>(data),
    };
  }

  LibraryBookRow copyWith({String? id, String? data}) =>
      LibraryBookRow(id: id ?? this.id, data: data ?? this.data);
  LibraryBookRow copyWithCompanion(StoredBooksCompanion data) {
    return LibraryBookRow(
      id: data.id.present ? data.id.value : this.id,
      data: data.data.present ? data.data.value : this.data,
    );
  }

  @override
  String toString() {
    return (StringBuffer('LibraryBookRow(')
          ..write('id: $id, ')
          ..write('data: $data')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(id, data);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is LibraryBookRow &&
          other.id == this.id &&
          other.data == this.data);
}

class StoredBooksCompanion extends UpdateCompanion<LibraryBookRow> {
  final Value<String> id;
  final Value<String> data;
  final Value<int> rowid;
  const StoredBooksCompanion({
    this.id = const Value.absent(),
    this.data = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  StoredBooksCompanion.insert({
    required String id,
    required String data,
    this.rowid = const Value.absent(),
  }) : id = Value(id),
       data = Value(data);
  static Insertable<LibraryBookRow> custom({
    Expression<String>? id,
    Expression<String>? data,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (data != null) 'data': data,
      if (rowid != null) 'rowid': rowid,
    });
  }

  StoredBooksCompanion copyWith({
    Value<String>? id,
    Value<String>? data,
    Value<int>? rowid,
  }) {
    return StoredBooksCompanion(
      id: id ?? this.id,
      data: data ?? this.data,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<String>(id.value);
    }
    if (data.present) {
      map['data'] = Variable<String>(data.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('StoredBooksCompanion(')
          ..write('id: $id, ')
          ..write('data: $data, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $StoredSourcesTable extends StoredSources
    with TableInfo<$StoredSourcesTable, LibrarySourceRow> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $StoredSourcesTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _idMeta = const VerificationMeta('id');
  @override
  late final GeneratedColumn<String> id = GeneratedColumn<String>(
    'id',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _dataMeta = const VerificationMeta('data');
  @override
  late final GeneratedColumn<String> data = GeneratedColumn<String>(
    'data',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [id, data];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'sources';
  @override
  VerificationContext validateIntegrity(
    Insertable<LibrarySourceRow> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    } else if (isInserting) {
      context.missing(_idMeta);
    }
    if (data.containsKey('data')) {
      context.handle(
        _dataMeta,
        this.data.isAcceptableOrUnknown(data['data']!, _dataMeta),
      );
    } else if (isInserting) {
      context.missing(_dataMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  LibrarySourceRow map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return LibrarySourceRow(
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}id'],
      )!,
      data: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}data'],
      )!,
    );
  }

  @override
  $StoredSourcesTable createAlias(String alias) {
    return $StoredSourcesTable(attachedDatabase, alias);
  }
}

class LibrarySourceRow extends DataClass
    implements Insertable<LibrarySourceRow> {
  final String id;
  final String data;
  const LibrarySourceRow({required this.id, required this.data});
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['id'] = Variable<String>(id);
    map['data'] = Variable<String>(data);
    return map;
  }

  StoredSourcesCompanion toCompanion(bool nullToAbsent) {
    return StoredSourcesCompanion(id: Value(id), data: Value(data));
  }

  factory LibrarySourceRow.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return LibrarySourceRow(
      id: serializer.fromJson<String>(json['id']),
      data: serializer.fromJson<String>(json['data']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<String>(id),
      'data': serializer.toJson<String>(data),
    };
  }

  LibrarySourceRow copyWith({String? id, String? data}) =>
      LibrarySourceRow(id: id ?? this.id, data: data ?? this.data);
  LibrarySourceRow copyWithCompanion(StoredSourcesCompanion data) {
    return LibrarySourceRow(
      id: data.id.present ? data.id.value : this.id,
      data: data.data.present ? data.data.value : this.data,
    );
  }

  @override
  String toString() {
    return (StringBuffer('LibrarySourceRow(')
          ..write('id: $id, ')
          ..write('data: $data')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(id, data);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is LibrarySourceRow &&
          other.id == this.id &&
          other.data == this.data);
}

class StoredSourcesCompanion extends UpdateCompanion<LibrarySourceRow> {
  final Value<String> id;
  final Value<String> data;
  final Value<int> rowid;
  const StoredSourcesCompanion({
    this.id = const Value.absent(),
    this.data = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  StoredSourcesCompanion.insert({
    required String id,
    required String data,
    this.rowid = const Value.absent(),
  }) : id = Value(id),
       data = Value(data);
  static Insertable<LibrarySourceRow> custom({
    Expression<String>? id,
    Expression<String>? data,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (data != null) 'data': data,
      if (rowid != null) 'rowid': rowid,
    });
  }

  StoredSourcesCompanion copyWith({
    Value<String>? id,
    Value<String>? data,
    Value<int>? rowid,
  }) {
    return StoredSourcesCompanion(
      id: id ?? this.id,
      data: data ?? this.data,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<String>(id.value);
    }
    if (data.present) {
      map['data'] = Variable<String>(data.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('StoredSourcesCompanion(')
          ..write('id: $id, ')
          ..write('data: $data, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $StoredSettingsTable extends StoredSettings
    with TableInfo<$StoredSettingsTable, LibrarySettingRow> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $StoredSettingsTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _idMeta = const VerificationMeta('id');
  @override
  late final GeneratedColumn<String> id = GeneratedColumn<String>(
    'id',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _valueMeta = const VerificationMeta('value');
  @override
  late final GeneratedColumn<String> value = GeneratedColumn<String>(
    'value',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [id, value];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'settings';
  @override
  VerificationContext validateIntegrity(
    Insertable<LibrarySettingRow> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    } else if (isInserting) {
      context.missing(_idMeta);
    }
    if (data.containsKey('value')) {
      context.handle(
        _valueMeta,
        value.isAcceptableOrUnknown(data['value']!, _valueMeta),
      );
    } else if (isInserting) {
      context.missing(_valueMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  LibrarySettingRow map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return LibrarySettingRow(
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}id'],
      )!,
      value: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}value'],
      )!,
    );
  }

  @override
  $StoredSettingsTable createAlias(String alias) {
    return $StoredSettingsTable(attachedDatabase, alias);
  }
}

class LibrarySettingRow extends DataClass
    implements Insertable<LibrarySettingRow> {
  final String id;
  final String value;
  const LibrarySettingRow({required this.id, required this.value});
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['id'] = Variable<String>(id);
    map['value'] = Variable<String>(value);
    return map;
  }

  StoredSettingsCompanion toCompanion(bool nullToAbsent) {
    return StoredSettingsCompanion(id: Value(id), value: Value(value));
  }

  factory LibrarySettingRow.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return LibrarySettingRow(
      id: serializer.fromJson<String>(json['id']),
      value: serializer.fromJson<String>(json['value']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<String>(id),
      'value': serializer.toJson<String>(value),
    };
  }

  LibrarySettingRow copyWith({String? id, String? value}) =>
      LibrarySettingRow(id: id ?? this.id, value: value ?? this.value);
  LibrarySettingRow copyWithCompanion(StoredSettingsCompanion data) {
    return LibrarySettingRow(
      id: data.id.present ? data.id.value : this.id,
      value: data.value.present ? data.value.value : this.value,
    );
  }

  @override
  String toString() {
    return (StringBuffer('LibrarySettingRow(')
          ..write('id: $id, ')
          ..write('value: $value')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(id, value);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is LibrarySettingRow &&
          other.id == this.id &&
          other.value == this.value);
}

class StoredSettingsCompanion extends UpdateCompanion<LibrarySettingRow> {
  final Value<String> id;
  final Value<String> value;
  final Value<int> rowid;
  const StoredSettingsCompanion({
    this.id = const Value.absent(),
    this.value = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  StoredSettingsCompanion.insert({
    required String id,
    required String value,
    this.rowid = const Value.absent(),
  }) : id = Value(id),
       value = Value(value);
  static Insertable<LibrarySettingRow> custom({
    Expression<String>? id,
    Expression<String>? value,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (value != null) 'value': value,
      if (rowid != null) 'rowid': rowid,
    });
  }

  StoredSettingsCompanion copyWith({
    Value<String>? id,
    Value<String>? value,
    Value<int>? rowid,
  }) {
    return StoredSettingsCompanion(
      id: id ?? this.id,
      value: value ?? this.value,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<String>(id.value);
    }
    if (value.present) {
      map['value'] = Variable<String>(value.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('StoredSettingsCompanion(')
          ..write('id: $id, ')
          ..write('value: $value, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $StoredChaptersTable extends StoredChapters
    with TableInfo<$StoredChaptersTable, LibraryChapterRow> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $StoredChaptersTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _bookIdMeta = const VerificationMeta('bookId');
  @override
  late final GeneratedColumn<String> bookId = GeneratedColumn<String>(
    'book_id',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _idMeta = const VerificationMeta('id');
  @override
  late final GeneratedColumn<String> id = GeneratedColumn<String>(
    'id',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _positionMeta = const VerificationMeta(
    'position',
  );
  @override
  late final GeneratedColumn<int> position = GeneratedColumn<int>(
    'position',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _dataMeta = const VerificationMeta('data');
  @override
  late final GeneratedColumn<String> data = GeneratedColumn<String>(
    'data',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [bookId, id, position, data];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'chapters';
  @override
  VerificationContext validateIntegrity(
    Insertable<LibraryChapterRow> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('book_id')) {
      context.handle(
        _bookIdMeta,
        bookId.isAcceptableOrUnknown(data['book_id']!, _bookIdMeta),
      );
    } else if (isInserting) {
      context.missing(_bookIdMeta);
    }
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    } else if (isInserting) {
      context.missing(_idMeta);
    }
    if (data.containsKey('position')) {
      context.handle(
        _positionMeta,
        position.isAcceptableOrUnknown(data['position']!, _positionMeta),
      );
    } else if (isInserting) {
      context.missing(_positionMeta);
    }
    if (data.containsKey('data')) {
      context.handle(
        _dataMeta,
        this.data.isAcceptableOrUnknown(data['data']!, _dataMeta),
      );
    } else if (isInserting) {
      context.missing(_dataMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {bookId, id};
  @override
  List<Set<GeneratedColumn>> get uniqueKeys => [
    {bookId, position},
  ];
  @override
  LibraryChapterRow map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return LibraryChapterRow(
      bookId: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}book_id'],
      )!,
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}id'],
      )!,
      position: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}position'],
      )!,
      data: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}data'],
      )!,
    );
  }

  @override
  $StoredChaptersTable createAlias(String alias) {
    return $StoredChaptersTable(attachedDatabase, alias);
  }
}

class LibraryChapterRow extends DataClass
    implements Insertable<LibraryChapterRow> {
  final String bookId;
  final String id;
  final int position;
  final String data;
  const LibraryChapterRow({
    required this.bookId,
    required this.id,
    required this.position,
    required this.data,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['book_id'] = Variable<String>(bookId);
    map['id'] = Variable<String>(id);
    map['position'] = Variable<int>(position);
    map['data'] = Variable<String>(data);
    return map;
  }

  StoredChaptersCompanion toCompanion(bool nullToAbsent) {
    return StoredChaptersCompanion(
      bookId: Value(bookId),
      id: Value(id),
      position: Value(position),
      data: Value(data),
    );
  }

  factory LibraryChapterRow.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return LibraryChapterRow(
      bookId: serializer.fromJson<String>(json['bookId']),
      id: serializer.fromJson<String>(json['id']),
      position: serializer.fromJson<int>(json['position']),
      data: serializer.fromJson<String>(json['data']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'bookId': serializer.toJson<String>(bookId),
      'id': serializer.toJson<String>(id),
      'position': serializer.toJson<int>(position),
      'data': serializer.toJson<String>(data),
    };
  }

  LibraryChapterRow copyWith({
    String? bookId,
    String? id,
    int? position,
    String? data,
  }) => LibraryChapterRow(
    bookId: bookId ?? this.bookId,
    id: id ?? this.id,
    position: position ?? this.position,
    data: data ?? this.data,
  );
  LibraryChapterRow copyWithCompanion(StoredChaptersCompanion data) {
    return LibraryChapterRow(
      bookId: data.bookId.present ? data.bookId.value : this.bookId,
      id: data.id.present ? data.id.value : this.id,
      position: data.position.present ? data.position.value : this.position,
      data: data.data.present ? data.data.value : this.data,
    );
  }

  @override
  String toString() {
    return (StringBuffer('LibraryChapterRow(')
          ..write('bookId: $bookId, ')
          ..write('id: $id, ')
          ..write('position: $position, ')
          ..write('data: $data')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(bookId, id, position, data);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is LibraryChapterRow &&
          other.bookId == this.bookId &&
          other.id == this.id &&
          other.position == this.position &&
          other.data == this.data);
}

class StoredChaptersCompanion extends UpdateCompanion<LibraryChapterRow> {
  final Value<String> bookId;
  final Value<String> id;
  final Value<int> position;
  final Value<String> data;
  final Value<int> rowid;
  const StoredChaptersCompanion({
    this.bookId = const Value.absent(),
    this.id = const Value.absent(),
    this.position = const Value.absent(),
    this.data = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  StoredChaptersCompanion.insert({
    required String bookId,
    required String id,
    required int position,
    required String data,
    this.rowid = const Value.absent(),
  }) : bookId = Value(bookId),
       id = Value(id),
       position = Value(position),
       data = Value(data);
  static Insertable<LibraryChapterRow> custom({
    Expression<String>? bookId,
    Expression<String>? id,
    Expression<int>? position,
    Expression<String>? data,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (bookId != null) 'book_id': bookId,
      if (id != null) 'id': id,
      if (position != null) 'position': position,
      if (data != null) 'data': data,
      if (rowid != null) 'rowid': rowid,
    });
  }

  StoredChaptersCompanion copyWith({
    Value<String>? bookId,
    Value<String>? id,
    Value<int>? position,
    Value<String>? data,
    Value<int>? rowid,
  }) {
    return StoredChaptersCompanion(
      bookId: bookId ?? this.bookId,
      id: id ?? this.id,
      position: position ?? this.position,
      data: data ?? this.data,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (bookId.present) {
      map['book_id'] = Variable<String>(bookId.value);
    }
    if (id.present) {
      map['id'] = Variable<String>(id.value);
    }
    if (position.present) {
      map['position'] = Variable<int>(position.value);
    }
    if (data.present) {
      map['data'] = Variable<String>(data.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('StoredChaptersCompanion(')
          ..write('bookId: $bookId, ')
          ..write('id: $id, ')
          ..write('position: $position, ')
          ..write('data: $data, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $StoredContentTable extends StoredContent
    with TableInfo<$StoredContentTable, LibraryContentRow> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $StoredContentTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _bookIdMeta = const VerificationMeta('bookId');
  @override
  late final GeneratedColumn<String> bookId = GeneratedColumn<String>(
    'book_id',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _chapterIdMeta = const VerificationMeta(
    'chapterId',
  );
  @override
  late final GeneratedColumn<String> chapterId = GeneratedColumn<String>(
    'chapter_id',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _valueMeta = const VerificationMeta('value');
  @override
  late final GeneratedColumn<String> value = GeneratedColumn<String>(
    'value',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [bookId, chapterId, value];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'content';
  @override
  VerificationContext validateIntegrity(
    Insertable<LibraryContentRow> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('book_id')) {
      context.handle(
        _bookIdMeta,
        bookId.isAcceptableOrUnknown(data['book_id']!, _bookIdMeta),
      );
    } else if (isInserting) {
      context.missing(_bookIdMeta);
    }
    if (data.containsKey('chapter_id')) {
      context.handle(
        _chapterIdMeta,
        chapterId.isAcceptableOrUnknown(data['chapter_id']!, _chapterIdMeta),
      );
    } else if (isInserting) {
      context.missing(_chapterIdMeta);
    }
    if (data.containsKey('value')) {
      context.handle(
        _valueMeta,
        value.isAcceptableOrUnknown(data['value']!, _valueMeta),
      );
    } else if (isInserting) {
      context.missing(_valueMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {bookId, chapterId};
  @override
  LibraryContentRow map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return LibraryContentRow(
      bookId: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}book_id'],
      )!,
      chapterId: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}chapter_id'],
      )!,
      value: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}value'],
      )!,
    );
  }

  @override
  $StoredContentTable createAlias(String alias) {
    return $StoredContentTable(attachedDatabase, alias);
  }
}

class LibraryContentRow extends DataClass
    implements Insertable<LibraryContentRow> {
  final String bookId;
  final String chapterId;
  final String value;
  const LibraryContentRow({
    required this.bookId,
    required this.chapterId,
    required this.value,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['book_id'] = Variable<String>(bookId);
    map['chapter_id'] = Variable<String>(chapterId);
    map['value'] = Variable<String>(value);
    return map;
  }

  StoredContentCompanion toCompanion(bool nullToAbsent) {
    return StoredContentCompanion(
      bookId: Value(bookId),
      chapterId: Value(chapterId),
      value: Value(value),
    );
  }

  factory LibraryContentRow.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return LibraryContentRow(
      bookId: serializer.fromJson<String>(json['bookId']),
      chapterId: serializer.fromJson<String>(json['chapterId']),
      value: serializer.fromJson<String>(json['value']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'bookId': serializer.toJson<String>(bookId),
      'chapterId': serializer.toJson<String>(chapterId),
      'value': serializer.toJson<String>(value),
    };
  }

  LibraryContentRow copyWith({
    String? bookId,
    String? chapterId,
    String? value,
  }) => LibraryContentRow(
    bookId: bookId ?? this.bookId,
    chapterId: chapterId ?? this.chapterId,
    value: value ?? this.value,
  );
  LibraryContentRow copyWithCompanion(StoredContentCompanion data) {
    return LibraryContentRow(
      bookId: data.bookId.present ? data.bookId.value : this.bookId,
      chapterId: data.chapterId.present ? data.chapterId.value : this.chapterId,
      value: data.value.present ? data.value.value : this.value,
    );
  }

  @override
  String toString() {
    return (StringBuffer('LibraryContentRow(')
          ..write('bookId: $bookId, ')
          ..write('chapterId: $chapterId, ')
          ..write('value: $value')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(bookId, chapterId, value);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is LibraryContentRow &&
          other.bookId == this.bookId &&
          other.chapterId == this.chapterId &&
          other.value == this.value);
}

class StoredContentCompanion extends UpdateCompanion<LibraryContentRow> {
  final Value<String> bookId;
  final Value<String> chapterId;
  final Value<String> value;
  final Value<int> rowid;
  const StoredContentCompanion({
    this.bookId = const Value.absent(),
    this.chapterId = const Value.absent(),
    this.value = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  StoredContentCompanion.insert({
    required String bookId,
    required String chapterId,
    required String value,
    this.rowid = const Value.absent(),
  }) : bookId = Value(bookId),
       chapterId = Value(chapterId),
       value = Value(value);
  static Insertable<LibraryContentRow> custom({
    Expression<String>? bookId,
    Expression<String>? chapterId,
    Expression<String>? value,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (bookId != null) 'book_id': bookId,
      if (chapterId != null) 'chapter_id': chapterId,
      if (value != null) 'value': value,
      if (rowid != null) 'rowid': rowid,
    });
  }

  StoredContentCompanion copyWith({
    Value<String>? bookId,
    Value<String>? chapterId,
    Value<String>? value,
    Value<int>? rowid,
  }) {
    return StoredContentCompanion(
      bookId: bookId ?? this.bookId,
      chapterId: chapterId ?? this.chapterId,
      value: value ?? this.value,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (bookId.present) {
      map['book_id'] = Variable<String>(bookId.value);
    }
    if (chapterId.present) {
      map['chapter_id'] = Variable<String>(chapterId.value);
    }
    if (value.present) {
      map['value'] = Variable<String>(value.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('StoredContentCompanion(')
          ..write('bookId: $bookId, ')
          ..write('chapterId: $chapterId, ')
          ..write('value: $value, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $StoredBackupFilesTable extends StoredBackupFiles
    with TableInfo<$StoredBackupFilesTable, LibraryBackupFileRow> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $StoredBackupFilesTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _idMeta = const VerificationMeta('id');
  @override
  late final GeneratedColumn<String> id = GeneratedColumn<String>(
    'id',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _valueMeta = const VerificationMeta('value');
  @override
  late final GeneratedColumn<String> value = GeneratedColumn<String>(
    'value',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [id, value];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'backup_files';
  @override
  VerificationContext validateIntegrity(
    Insertable<LibraryBackupFileRow> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    } else if (isInserting) {
      context.missing(_idMeta);
    }
    if (data.containsKey('value')) {
      context.handle(
        _valueMeta,
        value.isAcceptableOrUnknown(data['value']!, _valueMeta),
      );
    } else if (isInserting) {
      context.missing(_valueMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  LibraryBackupFileRow map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return LibraryBackupFileRow(
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}id'],
      )!,
      value: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}value'],
      )!,
    );
  }

  @override
  $StoredBackupFilesTable createAlias(String alias) {
    return $StoredBackupFilesTable(attachedDatabase, alias);
  }
}

class LibraryBackupFileRow extends DataClass
    implements Insertable<LibraryBackupFileRow> {
  final String id;
  final String value;
  const LibraryBackupFileRow({required this.id, required this.value});
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['id'] = Variable<String>(id);
    map['value'] = Variable<String>(value);
    return map;
  }

  StoredBackupFilesCompanion toCompanion(bool nullToAbsent) {
    return StoredBackupFilesCompanion(id: Value(id), value: Value(value));
  }

  factory LibraryBackupFileRow.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return LibraryBackupFileRow(
      id: serializer.fromJson<String>(json['id']),
      value: serializer.fromJson<String>(json['value']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<String>(id),
      'value': serializer.toJson<String>(value),
    };
  }

  LibraryBackupFileRow copyWith({String? id, String? value}) =>
      LibraryBackupFileRow(id: id ?? this.id, value: value ?? this.value);
  LibraryBackupFileRow copyWithCompanion(StoredBackupFilesCompanion data) {
    return LibraryBackupFileRow(
      id: data.id.present ? data.id.value : this.id,
      value: data.value.present ? data.value.value : this.value,
    );
  }

  @override
  String toString() {
    return (StringBuffer('LibraryBackupFileRow(')
          ..write('id: $id, ')
          ..write('value: $value')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(id, value);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is LibraryBackupFileRow &&
          other.id == this.id &&
          other.value == this.value);
}

class StoredBackupFilesCompanion extends UpdateCompanion<LibraryBackupFileRow> {
  final Value<String> id;
  final Value<String> value;
  final Value<int> rowid;
  const StoredBackupFilesCompanion({
    this.id = const Value.absent(),
    this.value = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  StoredBackupFilesCompanion.insert({
    required String id,
    required String value,
    this.rowid = const Value.absent(),
  }) : id = Value(id),
       value = Value(value);
  static Insertable<LibraryBackupFileRow> custom({
    Expression<String>? id,
    Expression<String>? value,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (value != null) 'value': value,
      if (rowid != null) 'rowid': rowid,
    });
  }

  StoredBackupFilesCompanion copyWith({
    Value<String>? id,
    Value<String>? value,
    Value<int>? rowid,
  }) {
    return StoredBackupFilesCompanion(
      id: id ?? this.id,
      value: value ?? this.value,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<String>(id.value);
    }
    if (value.present) {
      map['value'] = Variable<String>(value.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('StoredBackupFilesCompanion(')
          ..write('id: $id, ')
          ..write('value: $value, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $StoredArchiveEntriesTable extends StoredArchiveEntries
    with TableInfo<$StoredArchiveEntriesTable, LibraryArchiveEntryRow> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $StoredArchiveEntriesTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _idMeta = const VerificationMeta('id');
  @override
  late final GeneratedColumn<String> id = GeneratedColumn<String>(
    'id',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _valueMeta = const VerificationMeta('value');
  @override
  late final GeneratedColumn<Uint8List> value = GeneratedColumn<Uint8List>(
    'value',
    aliasedName,
    false,
    type: DriftSqlType.blob,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [id, value];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'archive_entries';
  @override
  VerificationContext validateIntegrity(
    Insertable<LibraryArchiveEntryRow> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    } else if (isInserting) {
      context.missing(_idMeta);
    }
    if (data.containsKey('value')) {
      context.handle(
        _valueMeta,
        value.isAcceptableOrUnknown(data['value']!, _valueMeta),
      );
    } else if (isInserting) {
      context.missing(_valueMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  LibraryArchiveEntryRow map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return LibraryArchiveEntryRow(
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}id'],
      )!,
      value: attachedDatabase.typeMapping.read(
        DriftSqlType.blob,
        data['${effectivePrefix}value'],
      )!,
    );
  }

  @override
  $StoredArchiveEntriesTable createAlias(String alias) {
    return $StoredArchiveEntriesTable(attachedDatabase, alias);
  }
}

class LibraryArchiveEntryRow extends DataClass
    implements Insertable<LibraryArchiveEntryRow> {
  final String id;
  final Uint8List value;
  const LibraryArchiveEntryRow({required this.id, required this.value});
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['id'] = Variable<String>(id);
    map['value'] = Variable<Uint8List>(value);
    return map;
  }

  StoredArchiveEntriesCompanion toCompanion(bool nullToAbsent) {
    return StoredArchiveEntriesCompanion(id: Value(id), value: Value(value));
  }

  factory LibraryArchiveEntryRow.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return LibraryArchiveEntryRow(
      id: serializer.fromJson<String>(json['id']),
      value: serializer.fromJson<Uint8List>(json['value']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<String>(id),
      'value': serializer.toJson<Uint8List>(value),
    };
  }

  LibraryArchiveEntryRow copyWith({String? id, Uint8List? value}) =>
      LibraryArchiveEntryRow(id: id ?? this.id, value: value ?? this.value);
  LibraryArchiveEntryRow copyWithCompanion(StoredArchiveEntriesCompanion data) {
    return LibraryArchiveEntryRow(
      id: data.id.present ? data.id.value : this.id,
      value: data.value.present ? data.value.value : this.value,
    );
  }

  @override
  String toString() {
    return (StringBuffer('LibraryArchiveEntryRow(')
          ..write('id: $id, ')
          ..write('value: $value')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(id, $driftBlobEquality.hash(value));
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is LibraryArchiveEntryRow &&
          other.id == this.id &&
          $driftBlobEquality.equals(other.value, this.value));
}

class StoredArchiveEntriesCompanion
    extends UpdateCompanion<LibraryArchiveEntryRow> {
  final Value<String> id;
  final Value<Uint8List> value;
  final Value<int> rowid;
  const StoredArchiveEntriesCompanion({
    this.id = const Value.absent(),
    this.value = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  StoredArchiveEntriesCompanion.insert({
    required String id,
    required Uint8List value,
    this.rowid = const Value.absent(),
  }) : id = Value(id),
       value = Value(value);
  static Insertable<LibraryArchiveEntryRow> custom({
    Expression<String>? id,
    Expression<Uint8List>? value,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (value != null) 'value': value,
      if (rowid != null) 'rowid': rowid,
    });
  }

  StoredArchiveEntriesCompanion copyWith({
    Value<String>? id,
    Value<Uint8List>? value,
    Value<int>? rowid,
  }) {
    return StoredArchiveEntriesCompanion(
      id: id ?? this.id,
      value: value ?? this.value,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<String>(id.value);
    }
    if (value.present) {
      map['value'] = Variable<Uint8List>(value.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('StoredArchiveEntriesCompanion(')
          ..write('id: $id, ')
          ..write('value: $value, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

abstract class _$LibraryDatabase extends GeneratedDatabase {
  _$LibraryDatabase(QueryExecutor e) : super(e);
  $LibraryDatabaseManager get managers => $LibraryDatabaseManager(this);
  late final $StoredBooksTable storedBooks = $StoredBooksTable(this);
  late final $StoredSourcesTable storedSources = $StoredSourcesTable(this);
  late final $StoredSettingsTable storedSettings = $StoredSettingsTable(this);
  late final $StoredChaptersTable storedChapters = $StoredChaptersTable(this);
  late final $StoredContentTable storedContent = $StoredContentTable(this);
  late final $StoredBackupFilesTable storedBackupFiles =
      $StoredBackupFilesTable(this);
  late final $StoredArchiveEntriesTable storedArchiveEntries =
      $StoredArchiveEntriesTable(this);
  @override
  Iterable<TableInfo<Table, Object?>> get allTables =>
      allSchemaEntities.whereType<TableInfo<Table, Object?>>();
  @override
  List<DatabaseSchemaEntity> get allSchemaEntities => [
    storedBooks,
    storedSources,
    storedSettings,
    storedChapters,
    storedContent,
    storedBackupFiles,
    storedArchiveEntries,
  ];
}

typedef $$StoredBooksTableCreateCompanionBuilder =
    StoredBooksCompanion Function({
      required String id,
      required String data,
      Value<int> rowid,
    });
typedef $$StoredBooksTableUpdateCompanionBuilder =
    StoredBooksCompanion Function({
      Value<String> id,
      Value<String> data,
      Value<int> rowid,
    });

class $$StoredBooksTableFilterComposer
    extends Composer<_$LibraryDatabase, $StoredBooksTable> {
  $$StoredBooksTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get data => $composableBuilder(
    column: $table.data,
    builder: (column) => ColumnFilters(column),
  );
}

class $$StoredBooksTableOrderingComposer
    extends Composer<_$LibraryDatabase, $StoredBooksTable> {
  $$StoredBooksTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get data => $composableBuilder(
    column: $table.data,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$StoredBooksTableAnnotationComposer
    extends Composer<_$LibraryDatabase, $StoredBooksTable> {
  $$StoredBooksTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<String> get data =>
      $composableBuilder(column: $table.data, builder: (column) => column);
}

class $$StoredBooksTableTableManager
    extends
        RootTableManager<
          _$LibraryDatabase,
          $StoredBooksTable,
          LibraryBookRow,
          $$StoredBooksTableFilterComposer,
          $$StoredBooksTableOrderingComposer,
          $$StoredBooksTableAnnotationComposer,
          $$StoredBooksTableCreateCompanionBuilder,
          $$StoredBooksTableUpdateCompanionBuilder,
          (
            LibraryBookRow,
            BaseReferences<
              _$LibraryDatabase,
              $StoredBooksTable,
              LibraryBookRow
            >,
          ),
          LibraryBookRow,
          PrefetchHooks Function()
        > {
  $$StoredBooksTableTableManager(_$LibraryDatabase db, $StoredBooksTable table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$StoredBooksTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$StoredBooksTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$StoredBooksTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback: ({
            Value<String> id = const Value.absent(),
            Value<String> data = const Value.absent(),
            Value<int> rowid = const Value.absent(),
          }) => StoredBooksCompanion(id: id, data: data, rowid: rowid),
          createCompanionCallback: ({
            required String id,
            required String data,
            Value<int> rowid = const Value.absent(),
          }) => StoredBooksCompanion.insert(id: id, data: data, rowid: rowid),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (
                  e.readTable<$StoredBooksTable, LibraryBookRow>(table),
                  BaseReferences<
                    _$LibraryDatabase,
                    $StoredBooksTable,
                    LibraryBookRow
                  >(db, table, e),
                ),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$StoredBooksTableProcessedTableManager =
    ProcessedTableManager<
      _$LibraryDatabase,
      $StoredBooksTable,
      LibraryBookRow,
      $$StoredBooksTableFilterComposer,
      $$StoredBooksTableOrderingComposer,
      $$StoredBooksTableAnnotationComposer,
      $$StoredBooksTableCreateCompanionBuilder,
      $$StoredBooksTableUpdateCompanionBuilder,
      (
        LibraryBookRow,
        BaseReferences<_$LibraryDatabase, $StoredBooksTable, LibraryBookRow>,
      ),
      LibraryBookRow,
      PrefetchHooks Function()
    >;
typedef $$StoredSourcesTableCreateCompanionBuilder =
    StoredSourcesCompanion Function({
      required String id,
      required String data,
      Value<int> rowid,
    });
typedef $$StoredSourcesTableUpdateCompanionBuilder =
    StoredSourcesCompanion Function({
      Value<String> id,
      Value<String> data,
      Value<int> rowid,
    });

class $$StoredSourcesTableFilterComposer
    extends Composer<_$LibraryDatabase, $StoredSourcesTable> {
  $$StoredSourcesTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get data => $composableBuilder(
    column: $table.data,
    builder: (column) => ColumnFilters(column),
  );
}

class $$StoredSourcesTableOrderingComposer
    extends Composer<_$LibraryDatabase, $StoredSourcesTable> {
  $$StoredSourcesTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get data => $composableBuilder(
    column: $table.data,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$StoredSourcesTableAnnotationComposer
    extends Composer<_$LibraryDatabase, $StoredSourcesTable> {
  $$StoredSourcesTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<String> get data =>
      $composableBuilder(column: $table.data, builder: (column) => column);
}

class $$StoredSourcesTableTableManager
    extends
        RootTableManager<
          _$LibraryDatabase,
          $StoredSourcesTable,
          LibrarySourceRow,
          $$StoredSourcesTableFilterComposer,
          $$StoredSourcesTableOrderingComposer,
          $$StoredSourcesTableAnnotationComposer,
          $$StoredSourcesTableCreateCompanionBuilder,
          $$StoredSourcesTableUpdateCompanionBuilder,
          (
            LibrarySourceRow,
            BaseReferences<
              _$LibraryDatabase,
              $StoredSourcesTable,
              LibrarySourceRow
            >,
          ),
          LibrarySourceRow,
          PrefetchHooks Function()
        > {
  $$StoredSourcesTableTableManager(
    _$LibraryDatabase db,
    $StoredSourcesTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$StoredSourcesTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$StoredSourcesTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$StoredSourcesTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback: ({
            Value<String> id = const Value.absent(),
            Value<String> data = const Value.absent(),
            Value<int> rowid = const Value.absent(),
          }) => StoredSourcesCompanion(id: id, data: data, rowid: rowid),
          createCompanionCallback: ({
            required String id,
            required String data,
            Value<int> rowid = const Value.absent(),
          }) => StoredSourcesCompanion.insert(id: id, data: data, rowid: rowid),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (
                  e.readTable<$StoredSourcesTable, LibrarySourceRow>(table),
                  BaseReferences<
                    _$LibraryDatabase,
                    $StoredSourcesTable,
                    LibrarySourceRow
                  >(db, table, e),
                ),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$StoredSourcesTableProcessedTableManager =
    ProcessedTableManager<
      _$LibraryDatabase,
      $StoredSourcesTable,
      LibrarySourceRow,
      $$StoredSourcesTableFilterComposer,
      $$StoredSourcesTableOrderingComposer,
      $$StoredSourcesTableAnnotationComposer,
      $$StoredSourcesTableCreateCompanionBuilder,
      $$StoredSourcesTableUpdateCompanionBuilder,
      (
        LibrarySourceRow,
        BaseReferences<
          _$LibraryDatabase,
          $StoredSourcesTable,
          LibrarySourceRow
        >,
      ),
      LibrarySourceRow,
      PrefetchHooks Function()
    >;
typedef $$StoredSettingsTableCreateCompanionBuilder =
    StoredSettingsCompanion Function({
      required String id,
      required String value,
      Value<int> rowid,
    });
typedef $$StoredSettingsTableUpdateCompanionBuilder =
    StoredSettingsCompanion Function({
      Value<String> id,
      Value<String> value,
      Value<int> rowid,
    });

class $$StoredSettingsTableFilterComposer
    extends Composer<_$LibraryDatabase, $StoredSettingsTable> {
  $$StoredSettingsTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get value => $composableBuilder(
    column: $table.value,
    builder: (column) => ColumnFilters(column),
  );
}

class $$StoredSettingsTableOrderingComposer
    extends Composer<_$LibraryDatabase, $StoredSettingsTable> {
  $$StoredSettingsTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get value => $composableBuilder(
    column: $table.value,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$StoredSettingsTableAnnotationComposer
    extends Composer<_$LibraryDatabase, $StoredSettingsTable> {
  $$StoredSettingsTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<String> get value =>
      $composableBuilder(column: $table.value, builder: (column) => column);
}

class $$StoredSettingsTableTableManager
    extends
        RootTableManager<
          _$LibraryDatabase,
          $StoredSettingsTable,
          LibrarySettingRow,
          $$StoredSettingsTableFilterComposer,
          $$StoredSettingsTableOrderingComposer,
          $$StoredSettingsTableAnnotationComposer,
          $$StoredSettingsTableCreateCompanionBuilder,
          $$StoredSettingsTableUpdateCompanionBuilder,
          (
            LibrarySettingRow,
            BaseReferences<
              _$LibraryDatabase,
              $StoredSettingsTable,
              LibrarySettingRow
            >,
          ),
          LibrarySettingRow,
          PrefetchHooks Function()
        > {
  $$StoredSettingsTableTableManager(
    _$LibraryDatabase db,
    $StoredSettingsTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$StoredSettingsTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$StoredSettingsTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$StoredSettingsTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback: ({
            Value<String> id = const Value.absent(),
            Value<String> value = const Value.absent(),
            Value<int> rowid = const Value.absent(),
          }) => StoredSettingsCompanion(id: id, value: value, rowid: rowid),
          createCompanionCallback:
              ({
                required String id,
                required String value,
                Value<int> rowid = const Value.absent(),
              }) => StoredSettingsCompanion.insert(
                id: id,
                value: value,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (
                  e.readTable<$StoredSettingsTable, LibrarySettingRow>(table),
                  BaseReferences<
                    _$LibraryDatabase,
                    $StoredSettingsTable,
                    LibrarySettingRow
                  >(db, table, e),
                ),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$StoredSettingsTableProcessedTableManager =
    ProcessedTableManager<
      _$LibraryDatabase,
      $StoredSettingsTable,
      LibrarySettingRow,
      $$StoredSettingsTableFilterComposer,
      $$StoredSettingsTableOrderingComposer,
      $$StoredSettingsTableAnnotationComposer,
      $$StoredSettingsTableCreateCompanionBuilder,
      $$StoredSettingsTableUpdateCompanionBuilder,
      (
        LibrarySettingRow,
        BaseReferences<
          _$LibraryDatabase,
          $StoredSettingsTable,
          LibrarySettingRow
        >,
      ),
      LibrarySettingRow,
      PrefetchHooks Function()
    >;
typedef $$StoredChaptersTableCreateCompanionBuilder =
    StoredChaptersCompanion Function({
      required String bookId,
      required String id,
      required int position,
      required String data,
      Value<int> rowid,
    });
typedef $$StoredChaptersTableUpdateCompanionBuilder =
    StoredChaptersCompanion Function({
      Value<String> bookId,
      Value<String> id,
      Value<int> position,
      Value<String> data,
      Value<int> rowid,
    });

class $$StoredChaptersTableFilterComposer
    extends Composer<_$LibraryDatabase, $StoredChaptersTable> {
  $$StoredChaptersTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get bookId => $composableBuilder(
    column: $table.bookId,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get position => $composableBuilder(
    column: $table.position,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get data => $composableBuilder(
    column: $table.data,
    builder: (column) => ColumnFilters(column),
  );
}

class $$StoredChaptersTableOrderingComposer
    extends Composer<_$LibraryDatabase, $StoredChaptersTable> {
  $$StoredChaptersTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get bookId => $composableBuilder(
    column: $table.bookId,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get position => $composableBuilder(
    column: $table.position,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get data => $composableBuilder(
    column: $table.data,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$StoredChaptersTableAnnotationComposer
    extends Composer<_$LibraryDatabase, $StoredChaptersTable> {
  $$StoredChaptersTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get bookId =>
      $composableBuilder(column: $table.bookId, builder: (column) => column);

  GeneratedColumn<String> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<int> get position =>
      $composableBuilder(column: $table.position, builder: (column) => column);

  GeneratedColumn<String> get data =>
      $composableBuilder(column: $table.data, builder: (column) => column);
}

class $$StoredChaptersTableTableManager
    extends
        RootTableManager<
          _$LibraryDatabase,
          $StoredChaptersTable,
          LibraryChapterRow,
          $$StoredChaptersTableFilterComposer,
          $$StoredChaptersTableOrderingComposer,
          $$StoredChaptersTableAnnotationComposer,
          $$StoredChaptersTableCreateCompanionBuilder,
          $$StoredChaptersTableUpdateCompanionBuilder,
          (
            LibraryChapterRow,
            BaseReferences<
              _$LibraryDatabase,
              $StoredChaptersTable,
              LibraryChapterRow
            >,
          ),
          LibraryChapterRow,
          PrefetchHooks Function()
        > {
  $$StoredChaptersTableTableManager(
    _$LibraryDatabase db,
    $StoredChaptersTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$StoredChaptersTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$StoredChaptersTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$StoredChaptersTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<String> bookId = const Value.absent(),
                Value<String> id = const Value.absent(),
                Value<int> position = const Value.absent(),
                Value<String> data = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => StoredChaptersCompanion(
                bookId: bookId,
                id: id,
                position: position,
                data: data,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                required String bookId,
                required String id,
                required int position,
                required String data,
                Value<int> rowid = const Value.absent(),
              }) => StoredChaptersCompanion.insert(
                bookId: bookId,
                id: id,
                position: position,
                data: data,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (
                  e.readTable<$StoredChaptersTable, LibraryChapterRow>(table),
                  BaseReferences<
                    _$LibraryDatabase,
                    $StoredChaptersTable,
                    LibraryChapterRow
                  >(db, table, e),
                ),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$StoredChaptersTableProcessedTableManager =
    ProcessedTableManager<
      _$LibraryDatabase,
      $StoredChaptersTable,
      LibraryChapterRow,
      $$StoredChaptersTableFilterComposer,
      $$StoredChaptersTableOrderingComposer,
      $$StoredChaptersTableAnnotationComposer,
      $$StoredChaptersTableCreateCompanionBuilder,
      $$StoredChaptersTableUpdateCompanionBuilder,
      (
        LibraryChapterRow,
        BaseReferences<
          _$LibraryDatabase,
          $StoredChaptersTable,
          LibraryChapterRow
        >,
      ),
      LibraryChapterRow,
      PrefetchHooks Function()
    >;
typedef $$StoredContentTableCreateCompanionBuilder =
    StoredContentCompanion Function({
      required String bookId,
      required String chapterId,
      required String value,
      Value<int> rowid,
    });
typedef $$StoredContentTableUpdateCompanionBuilder =
    StoredContentCompanion Function({
      Value<String> bookId,
      Value<String> chapterId,
      Value<String> value,
      Value<int> rowid,
    });

class $$StoredContentTableFilterComposer
    extends Composer<_$LibraryDatabase, $StoredContentTable> {
  $$StoredContentTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get bookId => $composableBuilder(
    column: $table.bookId,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get chapterId => $composableBuilder(
    column: $table.chapterId,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get value => $composableBuilder(
    column: $table.value,
    builder: (column) => ColumnFilters(column),
  );
}

class $$StoredContentTableOrderingComposer
    extends Composer<_$LibraryDatabase, $StoredContentTable> {
  $$StoredContentTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get bookId => $composableBuilder(
    column: $table.bookId,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get chapterId => $composableBuilder(
    column: $table.chapterId,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get value => $composableBuilder(
    column: $table.value,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$StoredContentTableAnnotationComposer
    extends Composer<_$LibraryDatabase, $StoredContentTable> {
  $$StoredContentTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get bookId =>
      $composableBuilder(column: $table.bookId, builder: (column) => column);

  GeneratedColumn<String> get chapterId =>
      $composableBuilder(column: $table.chapterId, builder: (column) => column);

  GeneratedColumn<String> get value =>
      $composableBuilder(column: $table.value, builder: (column) => column);
}

class $$StoredContentTableTableManager
    extends
        RootTableManager<
          _$LibraryDatabase,
          $StoredContentTable,
          LibraryContentRow,
          $$StoredContentTableFilterComposer,
          $$StoredContentTableOrderingComposer,
          $$StoredContentTableAnnotationComposer,
          $$StoredContentTableCreateCompanionBuilder,
          $$StoredContentTableUpdateCompanionBuilder,
          (
            LibraryContentRow,
            BaseReferences<
              _$LibraryDatabase,
              $StoredContentTable,
              LibraryContentRow
            >,
          ),
          LibraryContentRow,
          PrefetchHooks Function()
        > {
  $$StoredContentTableTableManager(
    _$LibraryDatabase db,
    $StoredContentTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$StoredContentTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$StoredContentTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$StoredContentTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<String> bookId = const Value.absent(),
                Value<String> chapterId = const Value.absent(),
                Value<String> value = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => StoredContentCompanion(
                bookId: bookId,
                chapterId: chapterId,
                value: value,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                required String bookId,
                required String chapterId,
                required String value,
                Value<int> rowid = const Value.absent(),
              }) => StoredContentCompanion.insert(
                bookId: bookId,
                chapterId: chapterId,
                value: value,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (
                  e.readTable<$StoredContentTable, LibraryContentRow>(table),
                  BaseReferences<
                    _$LibraryDatabase,
                    $StoredContentTable,
                    LibraryContentRow
                  >(db, table, e),
                ),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$StoredContentTableProcessedTableManager =
    ProcessedTableManager<
      _$LibraryDatabase,
      $StoredContentTable,
      LibraryContentRow,
      $$StoredContentTableFilterComposer,
      $$StoredContentTableOrderingComposer,
      $$StoredContentTableAnnotationComposer,
      $$StoredContentTableCreateCompanionBuilder,
      $$StoredContentTableUpdateCompanionBuilder,
      (
        LibraryContentRow,
        BaseReferences<
          _$LibraryDatabase,
          $StoredContentTable,
          LibraryContentRow
        >,
      ),
      LibraryContentRow,
      PrefetchHooks Function()
    >;
typedef $$StoredBackupFilesTableCreateCompanionBuilder =
    StoredBackupFilesCompanion Function({
      required String id,
      required String value,
      Value<int> rowid,
    });
typedef $$StoredBackupFilesTableUpdateCompanionBuilder =
    StoredBackupFilesCompanion Function({
      Value<String> id,
      Value<String> value,
      Value<int> rowid,
    });

class $$StoredBackupFilesTableFilterComposer
    extends Composer<_$LibraryDatabase, $StoredBackupFilesTable> {
  $$StoredBackupFilesTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get value => $composableBuilder(
    column: $table.value,
    builder: (column) => ColumnFilters(column),
  );
}

class $$StoredBackupFilesTableOrderingComposer
    extends Composer<_$LibraryDatabase, $StoredBackupFilesTable> {
  $$StoredBackupFilesTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get value => $composableBuilder(
    column: $table.value,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$StoredBackupFilesTableAnnotationComposer
    extends Composer<_$LibraryDatabase, $StoredBackupFilesTable> {
  $$StoredBackupFilesTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<String> get value =>
      $composableBuilder(column: $table.value, builder: (column) => column);
}

class $$StoredBackupFilesTableTableManager
    extends
        RootTableManager<
          _$LibraryDatabase,
          $StoredBackupFilesTable,
          LibraryBackupFileRow,
          $$StoredBackupFilesTableFilterComposer,
          $$StoredBackupFilesTableOrderingComposer,
          $$StoredBackupFilesTableAnnotationComposer,
          $$StoredBackupFilesTableCreateCompanionBuilder,
          $$StoredBackupFilesTableUpdateCompanionBuilder,
          (
            LibraryBackupFileRow,
            BaseReferences<
              _$LibraryDatabase,
              $StoredBackupFilesTable,
              LibraryBackupFileRow
            >,
          ),
          LibraryBackupFileRow,
          PrefetchHooks Function()
        > {
  $$StoredBackupFilesTableTableManager(
    _$LibraryDatabase db,
    $StoredBackupFilesTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$StoredBackupFilesTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$StoredBackupFilesTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$StoredBackupFilesTableAnnotationComposer(
                $db: db,
                $table: table,
              ),
          updateCompanionCallback: ({
            Value<String> id = const Value.absent(),
            Value<String> value = const Value.absent(),
            Value<int> rowid = const Value.absent(),
          }) => StoredBackupFilesCompanion(id: id, value: value, rowid: rowid),
          createCompanionCallback:
              ({
                required String id,
                required String value,
                Value<int> rowid = const Value.absent(),
              }) => StoredBackupFilesCompanion.insert(
                id: id,
                value: value,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (
                  e.readTable<$StoredBackupFilesTable, LibraryBackupFileRow>(
                    table,
                  ),
                  BaseReferences<
                    _$LibraryDatabase,
                    $StoredBackupFilesTable,
                    LibraryBackupFileRow
                  >(db, table, e),
                ),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$StoredBackupFilesTableProcessedTableManager =
    ProcessedTableManager<
      _$LibraryDatabase,
      $StoredBackupFilesTable,
      LibraryBackupFileRow,
      $$StoredBackupFilesTableFilterComposer,
      $$StoredBackupFilesTableOrderingComposer,
      $$StoredBackupFilesTableAnnotationComposer,
      $$StoredBackupFilesTableCreateCompanionBuilder,
      $$StoredBackupFilesTableUpdateCompanionBuilder,
      (
        LibraryBackupFileRow,
        BaseReferences<
          _$LibraryDatabase,
          $StoredBackupFilesTable,
          LibraryBackupFileRow
        >,
      ),
      LibraryBackupFileRow,
      PrefetchHooks Function()
    >;
typedef $$StoredArchiveEntriesTableCreateCompanionBuilder =
    StoredArchiveEntriesCompanion Function({
      required String id,
      required Uint8List value,
      Value<int> rowid,
    });
typedef $$StoredArchiveEntriesTableUpdateCompanionBuilder =
    StoredArchiveEntriesCompanion Function({
      Value<String> id,
      Value<Uint8List> value,
      Value<int> rowid,
    });

class $$StoredArchiveEntriesTableFilterComposer
    extends Composer<_$LibraryDatabase, $StoredArchiveEntriesTable> {
  $$StoredArchiveEntriesTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<Uint8List> get value => $composableBuilder(
    column: $table.value,
    builder: (column) => ColumnFilters(column),
  );
}

class $$StoredArchiveEntriesTableOrderingComposer
    extends Composer<_$LibraryDatabase, $StoredArchiveEntriesTable> {
  $$StoredArchiveEntriesTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<Uint8List> get value => $composableBuilder(
    column: $table.value,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$StoredArchiveEntriesTableAnnotationComposer
    extends Composer<_$LibraryDatabase, $StoredArchiveEntriesTable> {
  $$StoredArchiveEntriesTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<Uint8List> get value =>
      $composableBuilder(column: $table.value, builder: (column) => column);
}

class $$StoredArchiveEntriesTableTableManager
    extends
        RootTableManager<
          _$LibraryDatabase,
          $StoredArchiveEntriesTable,
          LibraryArchiveEntryRow,
          $$StoredArchiveEntriesTableFilterComposer,
          $$StoredArchiveEntriesTableOrderingComposer,
          $$StoredArchiveEntriesTableAnnotationComposer,
          $$StoredArchiveEntriesTableCreateCompanionBuilder,
          $$StoredArchiveEntriesTableUpdateCompanionBuilder,
          (
            LibraryArchiveEntryRow,
            BaseReferences<
              _$LibraryDatabase,
              $StoredArchiveEntriesTable,
              LibraryArchiveEntryRow
            >,
          ),
          LibraryArchiveEntryRow,
          PrefetchHooks Function()
        > {
  $$StoredArchiveEntriesTableTableManager(
    _$LibraryDatabase db,
    $StoredArchiveEntriesTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$StoredArchiveEntriesTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$StoredArchiveEntriesTableOrderingComposer(
                $db: db,
                $table: table,
              ),
          createComputedFieldComposer: () =>
              $$StoredArchiveEntriesTableAnnotationComposer(
                $db: db,
                $table: table,
              ),
          updateCompanionCallback:
              ({
                Value<String> id = const Value.absent(),
                Value<Uint8List> value = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => StoredArchiveEntriesCompanion(
                id: id,
                value: value,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                required String id,
                required Uint8List value,
                Value<int> rowid = const Value.absent(),
              }) => StoredArchiveEntriesCompanion.insert(
                id: id,
                value: value,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map(
                (e) => (
                  e.readTable<
                    $StoredArchiveEntriesTable,
                    LibraryArchiveEntryRow
                  >(table),
                  BaseReferences<
                    _$LibraryDatabase,
                    $StoredArchiveEntriesTable,
                    LibraryArchiveEntryRow
                  >(db, table, e),
                ),
              )
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$StoredArchiveEntriesTableProcessedTableManager =
    ProcessedTableManager<
      _$LibraryDatabase,
      $StoredArchiveEntriesTable,
      LibraryArchiveEntryRow,
      $$StoredArchiveEntriesTableFilterComposer,
      $$StoredArchiveEntriesTableOrderingComposer,
      $$StoredArchiveEntriesTableAnnotationComposer,
      $$StoredArchiveEntriesTableCreateCompanionBuilder,
      $$StoredArchiveEntriesTableUpdateCompanionBuilder,
      (
        LibraryArchiveEntryRow,
        BaseReferences<
          _$LibraryDatabase,
          $StoredArchiveEntriesTable,
          LibraryArchiveEntryRow
        >,
      ),
      LibraryArchiveEntryRow,
      PrefetchHooks Function()
    >;

class $LibraryDatabaseManager {
  final _$LibraryDatabase _db;
  $LibraryDatabaseManager(this._db);
  $$StoredBooksTableTableManager get storedBooks =>
      $$StoredBooksTableTableManager(_db, _db.storedBooks);
  $$StoredSourcesTableTableManager get storedSources =>
      $$StoredSourcesTableTableManager(_db, _db.storedSources);
  $$StoredSettingsTableTableManager get storedSettings =>
      $$StoredSettingsTableTableManager(_db, _db.storedSettings);
  $$StoredChaptersTableTableManager get storedChapters =>
      $$StoredChaptersTableTableManager(_db, _db.storedChapters);
  $$StoredContentTableTableManager get storedContent =>
      $$StoredContentTableTableManager(_db, _db.storedContent);
  $$StoredBackupFilesTableTableManager get storedBackupFiles =>
      $$StoredBackupFilesTableTableManager(_db, _db.storedBackupFiles);
  $$StoredArchiveEntriesTableTableManager get storedArchiveEntries =>
      $$StoredArchiveEntriesTableTableManager(_db, _db.storedArchiveEntries);
}
