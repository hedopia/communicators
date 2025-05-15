from datetime import datetime
import threading
import copy
import Queue as queue
import struct
import inspect
from collections import OrderedDict
import socket
import select
import time
import errno
import sys

class Event:
    """Class to handle the callbacks for a single event."""

    def __init__(self):
        """Initialize the event class."""
        self._callbacks = []

    def __iadd__(self, other):
        """Add a new callback to event."""
        self._callbacks.append(other)
        return self

    def __isub__(self, other):
        """Remove a callback from event."""
        self._callbacks.remove(other)
        return self

    def __call__(self, data):
        """Raise the event and call all callbacks."""
        for callback in self._callbacks:
            callback(data)

    def __len__(self):
        """Return the number of callbacks."""
        return len(self._callbacks)

    def __repr__(self):
        """Generate representation for an object."""
        return "{}: {}".format(self.__class__.__name__, self._callbacks)


class Targets:
    """Class to handle a list of objects as target for events."""

    def __init__(self):
        """Initialize the target class."""
        self._targets = []

    def __iadd__(self, other):
        """Add a targets."""
        self._targets.append(other)
        return self

    def __isub__(self, other):
        """Remove a target."""
        self._targets.remove(other)
        return self

    class _TargetsIter:
        def __init__(self, values):
            self._values = values
            self._counter = 0

        def __iter__(self):  # pragma: no cover
            """Return the iterator."""
            return self

        def next(self):
            """Get the next item or raise StopIteration if at end of list."""
            if self._counter < len(self._values):
                i = self._counter
                self._counter += 1
                return self._values[i]

            raise StopIteration()

    def __iter__(self):
        """Return the iterator."""
        return self._TargetsIter(self._targets)


class EventProducer:
    """Manages the consumers for the events and handles firing events."""

    def __init__(self):
        """Initialize the event producer class."""
        self._targets = Targets()
        self._events = {}

    def __getattr__(self, name):
        """Get an event as member of the EventProducer object."""
        if name not in self._events:
            self._events[name] = Event()

        return self._events[name]

    def __iadd__(self, other):
        """Add a the callbacks and targets of another EventProducer to this one."""
        for event_name in other._events:  # noqa
            if event_name not in self._events:
                self._events[event_name] = Event()

            for callback in other._events[event_name]._callbacks:  # noqa
                self._events[event_name] += callback

        for target in other._targets:  # noqa
            self._targets += target
        return self

    def fire(self, event, data):
        """
        Fire a event.

        calls all the available handlers for a specific event

        :param event: name of the event
        :type event: string
        :param data: data connected to this event
        :type data: dict
        """
        for target in self._targets:
            generic_handler = getattr(target, "_on_event", None)
            if callable(generic_handler):
                generic_handler(event, data)

            specific_handler = getattr(target, "_on_event_" + event, None)
            if callable(specific_handler):
                specific_handler(data)

        if event in self._events:
            self._events[event](data)

    def __repr__(self):
        """Generate representation for an object."""
        return "{}: {}".format(self.__class__.__name__, self._events)

    class _EventsIter:
        def __init__(self, keys):
            self._keys = list(keys)
            self._counter = 0

        def __iter__(self):  # pragma: no cover
            """Return the iterator."""
            return self

        def __next__(self):
            """Get the next item or raise StopIteration if at end of list."""
            if self._counter < len(self._keys):
                i = self._counter
                self._counter += 1
                return self._keys[i]

            raise StopIteration()

    def __iter__(self):
        """Return the iterator."""
        return self._EventsIter([event for event in self._events if len(self._events[event]) > 0])

    @property
    def targets(self):
        """Targets used as consumer for this producer."""
        return self._targets

    @targets.setter
    def targets(self, value):
        if self._targets != value:
            raise AttributeError("can't set attribute")

class _CallbackCallWrapper:
    def __init__(self, handler, name):
        self.name = name
        self.handler = handler

    def __call__(self, *args, **kwargs):
        return self.handler._call(self.name, *args, **kwargs)  # noqa


class CallbackHandler:
    """
    Handler for callbacks for HSMS/SECS/GEM events.

    This handler manages callbacks for events that can happen on a handler for a connection.
    """

    def __init__(self):
        """Initialize the handler."""
        self._callbacks = {}
        self.target = None
        self._object_intitialized = True

    def __setattr__(self, name, value):
        """
        Set an item as object member.

        :param name: Name of the callback
        :param value: Callback
        """
        if '_object_intitialized' not in self.__dict__ or name in self.__dict__:
            #dict.__setattr__(self, name, value)
            self.__dict__[name] = value
            return

        if value is None:
            if name in self._callbacks:
                del self._callbacks[name]
        else:
            self._callbacks[name] = value

    def __getattr__(self, name):
        """
        Get a callable function for an event.

        :param name: Name of the event
        :return: Callable representation of the callback
        """
        return _CallbackCallWrapper(self, name)

    class _CallbacksIter:
        def __init__(self, keys):
            self._keys = list(keys)
            self._counter = 0

        def __iter__(self):  # pragma: no cover
            return self

        def __next__(self):
            if self._counter < len(self._keys):
                i = self._counter
                self._counter += 1
                return self._keys[i]

            raise StopIteration()

    def __iter__(self):
        """
        Get an iterator for the callbacks.

        :return: Callback iterator.
        """
        return self._CallbacksIter(self._callbacks.keys())

    def __contains__(self, callback):
        """
        Check if a callback is present.

        :param callback: Name of the event
        :return: True if callback present
        """
        if callback in self._callbacks:
            return True

        delegate_handler = getattr(self.target, "_on_" + callback, None)
        if callable(delegate_handler):
            return True

        return False

    def _call(self, callback, *args, **kwargs):
        if callback in self._callbacks:
            return self._callbacks[callback](*args, **kwargs)

        delegate_handler = getattr(self.target, "_on_" + callback, None)
        if callable(delegate_handler):
            return delegate_handler(*args, **kwargs)

        return None

def add_metaclass(metaclass):
    """Class decorator for creating a class with a metaclass."""
    def wrapper(cls):
        orig_vars = cls.__dict__.copy()
        slots = orig_vars.get('__slots__')
        if slots is not None:
            if isinstance(slots, str):
                slots = [slots]
            for slots_var in slots:
                orig_vars.pop(slots_var)
        orig_vars.pop('__dict__', None)
        orig_vars.pop('__weakref__', None)
        if hasattr(cls, '__qualname__'):
            orig_vars['__qualname__'] = cls.__qualname__
        return metaclass(cls.__name__, cls.__bases__, orig_vars)
    return wrapper

def is_windows():
    """
    Returns True if running on windows.

    :returns: Is windows system
    :rtype: bool
    """
    if sys.platform == "win32":  # pragma: no cover
        return True

    return False

def indent_line(line, spaces=2):
    return (' ' * spaces) + line

def indent_block(block, spaces=2):
    lines = block.split('\n')
    lines = filter(None, lines)
    lines = map(lambda line, spc=spaces: indent_line(line, spc), lines)
    return '\n'.join(lines)


class FysomError(Exception):  # pragma: no cover
    """Fysom Error."""


class Fysom:  # pragma: no cover
    """Fysom state machine."""

    def __init__(self, cfg):
        """Initialize state machine."""
        self._apply(cfg)

    def isstate(self, state):
        """Get state."""
        return self.current == state

    def can(self, event):
        """Check if transition possible."""
        return event in self._map and self.current in self._map[event] \
            and not hasattr(self, 'transition')

    def cannot(self, event):
        """Check if transition is not possible."""
        return not self.can(event)

    def _apply(self, cfg):
        init = cfg['initial'] if 'initial' in cfg else None
        if isinstance(init, (str, bytes)):
            init = {'state': init}
        events = cfg['events'] if 'events' in cfg else []
        callbacks = cfg['callbacks'] if 'callbacks' in cfg else {}
        tmap = {}
        self._map = tmap
        self._autoforward = {}
        if 'autoforward' in cfg:
            for autoforward in cfg['autoforward']:
                self._autoforward[autoforward['src']] = autoforward['dst']

        def add(e):
            src = [e['src']] if isinstance(e['src'], (str, bytes)) else e['src']
            if e['name'] not in tmap:
                tmap[e['name']] = {}
            for s in src:
                tmap[e['name']][s] = e['dst']

        if init:
            if 'event' not in init:
                init['event'] = 'startup'
            add({'name': init['event'], 'src': 'none', 'dst': init['state']})

        for e in events:
            add(e)

        for name in tmap:
            setattr(self, name, self._build_event(name))

        for name in callbacks:
            setattr(self, name, callbacks[name])

        self.current = 'none'

        if init and 'defer' not in init:
            getattr(self, init['event'])()

    def _build_event(self, event):
        def fn(**kwargs):
            evt = event

            if hasattr(self, 'transition'):
                raise FysomError("event %s inappropriate because previous"
                                 " transition did not complete" % evt)
            if not self.can(evt):
                raise FysomError("event %s inappropriate in current state"
                                 " %s" % (evt, self.current))
            src = self.current
            dst = self._map[evt][src]

            transitionAvailable = True

            while transitionAvailable:
                class _e_obj:
                    pass
                e = _e_obj()
                e.fsm, e.event, e.src, e.dst = self, evt, src, dst
                for k in kwargs:
                    setattr(e, k, kwargs[k])

                if self.current != dst:
                    if self._before_event(e) is False:
                        return

                    def _tran():
                        delattr(self, 'transition')
                        self.current = dst
                        self._enter_state(e)
                        self._change_state(e)
                        self._after_event(e)
                    self.transition = _tran

                if self._leave_state(e) is not False:
                    if hasattr(self, 'transition'):
                        self.transition()

                if self.current in self._autoforward:
                    src = dst
                    dst = self._autoforward[src]
                    evt = "autoforward" + src + "-" + dst

                    transitionAvailable = True
                else:
                    transitionAvailable = False

        return fn

    def _before_event(self, e):
        fnname = 'onbefore' + e.event
        if hasattr(self, fnname):
            return getattr(self, fnname)(e)
        return None

    def _after_event(self, e):
        for fnname in ['onafter' + e.event, 'on' + e.event]:
            if hasattr(self, fnname):
                return getattr(self, fnname)(e)
        return None

    def _leave_state(self, e):
        fnname = 'onleave' + e.src
        if hasattr(self, fnname):
            return getattr(self, fnname)(e)
        return None

    def _enter_state(self, e):
        for fnname in ['onenter' + e.dst, 'on' + e.dst]:
            if hasattr(self, fnname):
                return getattr(self, fnname)(e)
        return None

    def _change_state(self, e):
        fnname = 'onchangestate'
        if hasattr(self, fnname):
            return getattr(self, fnname)(e)
        return None


class SecsVar:
    """
    Base class for SECS variables.

    Due to the python types, wrapper classes for variables are required.
    If constructor is called with SecsVar or subclass only the value is copied.
    """

    formatCode = -1

    def __init__(self):
        """Initialize a secs variable."""
        self.value = None

    @staticmethod
    def generate(dataformat):
        """
        Generate actual variable from data format.

        :param dataformat: dataformat to create variable for
        :type dataformat: list/SecsVar based class
        :returns: created variable
        :rtype: SecsVar based class
        """
        if dataformat is None:
            return None

        if isinstance(dataformat, list):
            if len(dataformat) == 1:
                return SecsVarArray(dataformat[0])
            return SecsVarList(dataformat)
        if inspect.isclass(dataformat):
            if issubclass(dataformat, SecsVar):
                return dataformat()
            raise TypeError("Can't generate item of class {}".format(dataformat.__name__))
        raise TypeError("Can't handle item of class {}".format(dataformat.__class__.__name__))

    @staticmethod
    def get_format(dataformat, showname=False):
        """
        Gets the format of the function.

        :returns: returns the string representation of the function
        :rtype: string
        """
        del showname  # unused variable
        if dataformat is None:
            return None

        if isinstance(dataformat, list):
            if len(dataformat) == 1:
                return SecsVarArray.get_format(dataformat[0])
            return SecsVarList.get_format(dataformat)

        if inspect.isclass(dataformat):
            if issubclass(dataformat, SecsVar):
                return dataformat.get_format()
            raise TypeError("Can't generate dataformat for class {}".format(dataformat.__name__))

        raise TypeError("Can't handle item of class {}".format(dataformat.__class__.__name__))

    def set(self, value):
        """
        Set the internal value to the provided value.

        :param value: new value
        :type value: various
        """
        raise NotImplementedError("Function set not implemented on " + self.__class__.__name__)

    def encode_item_header(self, length):
        """
        Encode item header depending on the number of length bytes required.

        :param length: number of bytes in data
        :type length: integer
        :returns: encoded item header bytes
        :rtype: string
        """
        if length < 0:
            raise ValueError("Encoding {} not possible, data length too small {}"
                             .format(self.__class__.__name__, length))
        if length > 0xFFFFFF:
            raise ValueError("Encoding {} not possible, data length too big {}"
                             .format(self.__class__.__name__, length))

        if length > 0xFFFF:
            length_bytes = 3
            format_byte = (self.formatCode << 2) | length_bytes
            return bytes(bytearray((format_byte, (length & 0xFF0000) >> 16, (length & 0x00FF00) >> 8,
                                    (length & 0x0000FF))))
        if length > 0xFF:
            length_bytes = 2
            format_byte = (self.formatCode << 2) | length_bytes
            return bytes(bytearray((format_byte, (length & 0x00FF00) >> 8, (length & 0x0000FF))))

        length_bytes = 1
        format_byte = (self.formatCode << 2) | length_bytes
        return bytes(bytearray((format_byte, (length & 0x0000FF))))

    def decode_item_header(self, data, text_pos=0):
        """
        Encode item header depending on the number of length bytes required.

        :param data: encoded data
        :type data: string
        :param text_pos: start of item header in data
        :type text_pos: integer
        :returns: start position for next item, format code, length item of data
        :rtype: (integer, integer, integer)
        """
        if len(data) == 0:
            raise ValueError("Decoding for {} without any text".format(self.__class__.__name__))

        # parse format byte
        format_byte = bytearray(data)[text_pos]

        format_code = (format_byte & 0b11111100) >> 2
        length_bytes = (format_byte & 0b00000011)

        text_pos += 1

        # read 1-3 length bytes
        length = 0
        for _ in range(length_bytes):
            length <<= 8
            length += bytearray(data)[text_pos]

            text_pos += 1

        if 0 <= self.formatCode != format_code:
            raise ValueError("Decoding data for {} ({}) has invalid format {}"
                             .format(self.__class__.__name__, self.formatCode, format_code))

        return text_pos, format_code, length


class SecsVarDynamic(SecsVar):
    """Variable with interchangable type."""

    def __init__(self, types, value=None, count=-1):
        """
        Initialize a dynamic secs variable.

        :param types: list of supported types, default first. empty means all types are support, SecsVarString default
        :type types: list of :class:`secsgem.secs.variables.SecsVar` classes
        :param value: initial value
        :type value: various
        :param count: max number of items in type
        :type count: integer
        """
        #super(SecsVarDynamic, self).__init__()
        SecsVar.__init__(self)

        self.value = None

        self.types = types
        self.count = count
        if value is not None:
            self.set(value)

    def __repr__(self):
        """Generate textual representation for an object of this class."""
        return self.value.__repr__()

    def __len__(self):
        """Get the length."""
        return self.value.__len__()

    def __getitem__(self, key):
        """Get an item using the indexer operator."""
        return self.value.__getitem__(key)

    def __setitem__(self, key, item):
        """Set an item using the indexer operator."""
        self.value.__setitem__(key, item)

    def __eq__(self, other):
        """Check equality with other object."""
        if isinstance(other, SecsVarDynamic):
            return other.value.value == self.value.value
        if isinstance(other, SecsVar):
            return other.value == self.value.value
        if isinstance(other, list):
            return other == self.value.value
        if isinstance(other, (bytes, str)) and isinstance(self.value.value, (bytes, str)):
            return str(other) == str(self.value.value)

        return [other] == self.value.value

    def __hash__(self):
        """Get data item for hashing."""
        if isinstance(self.value.value, list):
            return hash(self.value.value[0])
        return hash(self.value.value)

    def __type_supported(self, typ):
        if not self.types:
            return True

        if typ in self.types:
            return True

        return False

    def set(self, value):
        """
        Set the internal value to the provided value.

        In doubt provide the variable wrapped in the matching :class:`secsgem.secs.variables.SecsVar` class,
        to avoid confusion.

        **Example**::

            >>> import secsgem
            >>>
            >>> var = secsgem.SecsVarDynamic([secsgem.SecsVarString, secsgem.SecsVarU1])
            >>> var.set(secsgem.SecsVarU1(10))
            >>> var
            <U1 10 >

        If no type is provided the default type is used which might not be the expected type.

        :param value: new value
        :type value: various
        """
        if isinstance(value, SecsVar):
            if isinstance(value, SecsVarDynamic):
                if not isinstance(value.value, tuple(self.types)) and self.types:
                    raise ValueError("Unsupported type {} for this instance of SecsVarDynamic, allowed {}"
                                     .format(value.value.__class__.__name__, self.types))

                self.value = value.value
            else:
                if not isinstance(value, tuple(self.types)) and self.types:
                    raise ValueError("Unsupported type {} for this instance of SecsVarDynamic, allowed {}"
                                     .format(value.__class__.__name__, self.types))

                self.value = value
        else:
            matched_type = self._match_type(value)

            if matched_type is None:
                raise ValueError('Value "{}" of type {} not valid for SecsDynamic with {}'
                                 .format(value, value.__class__.__name__, self.types))

            self.value = matched_type(count=self.count)
            self.value.set(value)

    def get(self):
        """
        Return the internal value.

        :returns: internal value
        :rtype: various
        """
        if self.value is not None:
            return self.value.get()

        return None

    def encode(self):
        """
        Encode the value to secs data.

        :returns: encoded data bytes
        :rtype: string
        """
        return self.value.encode()

    def decode(self, data, start=0):
        """
        Decode the secs byte data to the value.

        :param data: encoded data bytes
        :type data: string
        :param start: start position of value the data
        :type start: integer
        :returns: new start position
        :rtype: integer
        """
        (_, format_code, _) = self.decode_item_header(data, start)

        if format_code == SecsVarArray.formatCode and self.__type_supported(SecsVarArray):
            self.value = SecsVarArray(ANYVALUE)
        elif format_code == SecsVarBinary.formatCode and self.__type_supported(SecsVarBinary):
            self.value = SecsVarBinary(count=self.count)
        elif format_code == SecsVarBoolean.formatCode and self.__type_supported(SecsVarBoolean):
            self.value = SecsVarBoolean(count=self.count)
        elif format_code == SecsVarString.formatCode and self.__type_supported(SecsVarString):
            self.value = SecsVarString(count=self.count)
        elif format_code == SecsVarI8.formatCode and self.__type_supported(SecsVarI8):
            self.value = SecsVarI8(count=self.count)
        elif format_code == SecsVarI1.formatCode and self.__type_supported(SecsVarI1):
            self.value = SecsVarI1(count=self.count)
        elif format_code == SecsVarI2.formatCode and self.__type_supported(SecsVarI2):
            self.value = SecsVarI2(count=self.count)
        elif format_code == SecsVarI4.formatCode and self.__type_supported(SecsVarI4):
            self.value = SecsVarI4(count=self.count)
        elif format_code == SecsVarF8.formatCode and self.__type_supported(SecsVarF8):
            self.value = SecsVarF8(count=self.count)
        elif format_code == SecsVarF4.formatCode and self.__type_supported(SecsVarF4):
            self.value = SecsVarF4(count=self.count)
        elif format_code == SecsVarU8.formatCode and self.__type_supported(SecsVarU8):
            self.value = SecsVarU8(count=self.count)
        elif format_code == SecsVarU1.formatCode and self.__type_supported(SecsVarU1):
            self.value = SecsVarU1(count=self.count)
        elif format_code == SecsVarU2.formatCode and self.__type_supported(SecsVarU2):
            self.value = SecsVarU2(count=self.count)
        elif format_code == SecsVarU4.formatCode and self.__type_supported(SecsVarU4):
            self.value = SecsVarU4(count=self.count)
        else:
            raise ValueError(
                "Unsupported format {} for this instance of SecsVarDynamic, allowed {}".format(
                    format_code,
                    self.types))

        return self.value.decode(data, start)

    def _match_type(self, value):
        var_types = self.types
        # if no types are set use internal order
        if not self.types:
            var_types = [SecsVarBoolean, SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4,
                         SecsVarI8, SecsVarF4, SecsVarF8, SecsVarString, SecsVarBinary]

        # first try to find the preferred type for the kind of value
        for var_type in var_types:
            if isinstance(value, tuple(var_type.preferredTypes)):
                if var_type(count=self.count).supports_value(value):
                    return var_type

        # when no preferred type was found, then try to match any available type
        for var_type in var_types:
            if var_type(count=self.count).supports_value(value):
                return var_type

        return None


class ANYVALUE(SecsVarDynamic):
    """
    Dummy data item for generation of unknown types.

    :Types:
       - :class:`SecsVarArray <secsgem.secs.variables.SecsVarArray>`
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarBoolean <secsgem.secs.variables.SecsVarBoolean>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarF8 <secsgem.secs.variables.SecsVarF8>`
       - :class:`SecsVarF4 <secsgem.secs.variables.SecsVarF4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    """

    def __init__(self, value=None):
        """
        Initialize an ANYVALUE variable.

        :param value: value of the variable
        """
        self.name = self.__class__.__name__

        #super(ANYVALUE, self).__init__([SecsVarArray, SecsVarBoolean, SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8,
        #                                SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8, SecsVarF4, SecsVarF8,
        #                                SecsVarString, SecsVarBinary], value=value)
        SecsVarDynamic.__init__(self, [SecsVarArray, SecsVarBoolean, SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8,
                                        SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8, SecsVarF4, SecsVarF8,
                                        SecsVarString, SecsVarBinary], value=value)


class SecsVarList(SecsVar):
    """List variable type. List with items of different types."""

    formatCode = 0
    textCode = 'L'
    preferredTypes = [dict]

    class _SecsVarListIter:
        def __init__(self, keys):
            self._keys = list(keys)
            self._counter = 0

        def __iter__(self):
            """Get an iterator."""
            return self

        def __next__(self):
            """Get the next item or raise StopIteration if at end of list."""
            if self._counter < len(self._keys):
                i = self._counter
                self._counter += 1
                return self._keys[i]

            raise StopIteration()

    def __init__(self, dataformat, value=None):
        """
        Initialize a secs list variable.

        :param dataformat: internal data values
        :type dataformat: OrderedDict
        :param value: initial value
        :type value: dict/list
        :param count: number of fields in the list
        :type count: integer
        """
        #super(SecsVarList, self).__init__()
        SecsVar.__init__(self)

        self.name = "DATA"

        self.data = self._generate(dataformat)

        if value is not None:
            self.set(value)

        self._object_intitialized = True

    @staticmethod
    def get_format(dataformat, showname=False):
        """
        Gets the format of the variable.

        :returns: returns the string representation of the function
        :rtype: string
        """
        if showname:
            arrayName = "{}: ".format(SecsVarList.get_name_from_format(dataformat))
        else:
            arrayName = ""

        if isinstance(dataformat, list):
            items = []
            for item in dataformat:
                if isinstance(item, str):
                    continue
                if isinstance(item, list):
                    if len(item) == 1:
                        items.append(indent_block(SecsVarArray.get_format(item[0], True), 4))
                    else:
                        items.append(indent_block(SecsVarList.get_format(item, True), 4))
                else:
                    items.append(indent_block(item.get_format(), 4))
            return arrayName + "{\n" + "\n".join(items) + "\n}"
        return None

    def __repr__(self):
        """Generate textual representation for an object of this class."""
        if len(self.data) == 0:
            return "<{}>".format(self.textCode)

        data = ""

        for field_name in self.data:
            data += "{}\n".format(indent_block(self.data[field_name].__repr__()))

        return "<{} [{}]\n{}\n>".format(self.textCode, len(self.data), data)

    def __len__(self):
        """Get the length."""
        return len(self.data)

    def __getitem__(self, index):
        """Get an item using the indexer operator."""
        if isinstance(index, int):
            return self.data[list(self.data.keys())[index]]
        return self.data[index]

    def __iter__(self):
        """Get an iterator."""
        return SecsVarList._SecsVarListIter(self.data.keys())

    def __setitem__(self, index, value):
        """Set an item using the indexer operator."""
        if isinstance(index, int):
            index = list(self.data.keys())[index]

        if isinstance(value, (type(self.data[index]), self.data[index].__class__.__bases__)):
            self.data[index] = value
        elif isinstance(value, SecsVar):
            raise TypeError("Wrong type {} when expecting {}".format(value.__class__.__name__,
                                                                     self.data[index].__class__.__name__))
        else:
            self.data[index].set(value)

    def _generate(self, dataformat):
        if dataformat is None:
            return None

        result_data = OrderedDict()
        for item in dataformat:
            if isinstance(item, str):
                self.name = item
                continue

            itemvalue = SecsVar.generate(item)
            if isinstance(itemvalue, SecsVarArray):
                result_data[itemvalue.name] = itemvalue
            elif isinstance(itemvalue, SecsVarList):
                result_data[SecsVarList.get_name_from_format(item)] = itemvalue
            elif isinstance(itemvalue, SecsVar):
                result_data[itemvalue.name] = itemvalue
            else:
                raise TypeError("Can't handle item of class {}".format(dataformat.__class__.__name__))

        return result_data

    def __getattr__(self, item):
        """Get an item as member of the object."""
        try:
            return self.data.__getitem__(item)
        except KeyError:
            raise AttributeError(item)

    def __setattr__(self, item, value):
        """Set an item as member of the object."""
        if '_object_intitialized' not in self.__dict__:
            #dict.__setattr__(self, item, value)
            self.__dict__[item] = value
            return

        if item in self.data:
            if isinstance(value, (type(self.data[item]), self.data[item].__class__.__bases__)):
                self.data[item] = value
            elif isinstance(value, SecsVar):
                raise TypeError("Wrong type {} when expecting {}".format(value.__class__.__name__,
                                                                         self.data[item].__class__.__name__))
            else:
                self.data[item].set(value)
        else:
            self.__dict__.__setattr__(item, value)

    @staticmethod
    def get_name_from_format(dataformat):
        """
        Generates a name for the passed dataformat.

        :param dataformat: dataformat to get name for
        :type dataformat: list/SecsVar based class
        :returns: name for dataformat
        :rtype: str
        """
        if not isinstance(dataformat, list):
            raise TypeError("Can't generate item name of class {}".format(dataformat.__class__.__name__))

        if isinstance(dataformat[0], str):
            return dataformat[0]

        return "DATA"

    def set(self, value):
        """
        Set the internal value to the provided value.

        :param value: new value
        :type value: dict/list
        """
        if isinstance(value, dict):
            for field_name in value:
                self.data[field_name].set(value[field_name])
        elif isinstance(value, list):
            if len(value) > len(self.data):
                raise ValueError("Value has invalid field count (expected: {}, actual: {})"
                                 .format(len(self.data), len(value)))

            counter = 0
            for itemvalue in value:
                self.data[list(self.data.keys())[counter]].set(itemvalue)
                counter += 1
        else:
            raise ValueError("Invalid value type {} for {}".format(type(value).__name__, self.__class__.__name__))

    def get(self):
        """
        Return the internal value.

        :returns: internal value
        :rtype: list
        """
        data = {}
        for field_name in self.data:
            data[field_name] = self.data[field_name].get()

        return data

    def encode(self):
        """
        Encode the value to secs data.

        :returns: encoded data bytes
        :rtype: string
        """
        result = self.encode_item_header(len(self.data))

        for field_name in self.data:
            result += self.data[field_name].encode()

        return result

    def decode(self, data, start=0):
        """
        Decode the secs byte data to the value.

        :param data: encoded data bytes
        :type data: string
        :param start: start position of value the data
        :type start: integer
        :returns: new start position
        :rtype: integer
        """
        (text_pos, _, length) = self.decode_item_header(data, start)

        # list
        for i in range(length):
            field_name = list(self.data.keys())[i]
            text_pos = self.data[field_name].decode(data, text_pos)

        return text_pos


class SecsVarArray(SecsVar):
    """List variable type. List with items of same type."""

    formatCode = 0
    textCode = 'L'
    preferredTypes = [list]

    class _SecsVarArrayIter:
        def __init__(self, values):
            self._values = values
            self._counter = 0

        def __iter__(self):
            """Get an iterator."""
            return self

        def __next__(self):
            """Get the next item or raise StopIteration if at end of list."""
            if self._counter < len(self._values):
                i = self._counter
                self._counter += 1
                return self._values[i]

            raise StopIteration()

    def __init__(self, dataFormat, value=None, count=-1):
        """
        Initialize a secs array variable.

        :param dataFormat: internal data definition/sample
        :type dataFormat: :class:`secsgem.secs.variables.SecsVar`
        :param value: initial value
        :type value: list
        :param count: number of fields in the list
        :type count: integer
        """
        #super(SecsVarArray, self).__init__()
        SecsVar.__init__(self)

        self.item_decriptor = dataFormat
        self.count = count
        self.data = []
        if isinstance(dataFormat, list):
            self.name = SecsVarList.get_name_from_format(dataFormat)
        elif hasattr(dataFormat, "__name__"):
            self.name = dataFormat.__name__
        else:
            self.name = "UNKNOWN"

        if value is not None:
            self.set(value)

    @staticmethod
    def get_format(dataformat, showname=False):
        """
        Gets the format of the variable.

        :returns: returns the string representation of the function
        :rtype: string
        """
        if showname:
            arrayName = "{}: "
            if isinstance(dataformat, list):
                arrayName = arrayName.format(SecsVarList.get_name_from_format(dataformat))
            else:
                arrayName = arrayName.format(dataformat.__name__)
        else:
            arrayName = ""

        if isinstance(dataformat, list):
            return "{}[\n{}\n    ...\n]".format(arrayName, indent_block(SecsVarList.get_format(dataformat), 4))

        return "{}[\n{}\n    ...\n]".format(arrayName, indent_block(dataformat.get_format(not showname), 4))

    def __repr__(self):
        """Generate textual representation for an object of this class."""
        if len(self.data) == 0:
            return "<{}>".format(self.textCode)

        data = ""

        for value in self.data:
            data += "{}\n".format(indent_block(value.__repr__()))

        return "<{} [{}]\n{}\n>".format(self.textCode, len(self.data), data)

    def __len__(self):
        """Get the length."""
        return len(self.data)

    def __getitem__(self, key):
        """Get an item using the indexer operator."""
        return self.data[key]

    def __iter__(self):
        """Get an iterator."""
        return SecsVarArray._SecsVarArrayIter(self.data)

    def __setitem__(self, key, value):
        """Set an item using the indexer operator."""
        if isinstance(value, (type(self.data[key]), self.data[key].__class__.__bases__)):
            self.data[key] = value
        elif isinstance(value, SecsVar):
            raise TypeError("Wrong type {} when expecting {}".format(value.__class__.__name__,
                                                                     self.data[key].__class__.__name__))
        else:
            self.data[key].set(value)

    def append(self, data):
        """
        Append data to the internal list.

        :param value: new value
        :type value: various
        """
        new_object = SecsVar.generate(self.item_decriptor)
        new_object.set(data)
        self.data.append(new_object)

    def set(self, value):
        """
        Set the internal value to the provided value.

        :param value: new value
        :type value: list
        """
        if not isinstance(value, list):
            raise ValueError("Invalid value type {} for {}".format(type(value).__name__, self.__class__.__name__))

        if self.count >= 0:
            if not len(value) == self.count:
                raise ValueError("Value has invalid field count (expected: {}, actual: {})"
                                 .format(self.count, len(value)))

        self.data = []

        for item in value:
            new_object = SecsVar.generate(self.item_decriptor)
            new_object.set(item)
            self.data.append(new_object)

    def get(self):
        """
        Return the internal value.

        :returns: internal value
        :rtype: list
        """
        data = []
        for item in self.data:
            data.append(item.get())

        return data

    def encode(self):
        """
        Encode the value to secs data.

        :returns: encoded data bytes
        :rtype: string
        """
        result = self.encode_item_header(len(self.data))

        for item in self.data:
            result += item.encode()

        return result

    def decode(self, data, start=0):
        """
        Decode the secs byte data to the value.

        :param data: encoded data bytes
        :type data: string
        :param start: start position of value the data
        :type start: integer
        :returns: new start position
        :rtype: integer
        """
        (text_pos, _, length) = self.decode_item_header(data, start)

        # list
        self.data = []

        for _ in range(length):
            new_object = SecsVar.generate(self.item_decriptor)
            text_pos = new_object.decode(data, text_pos)
            self.data.append(new_object)

        return text_pos


class SecsVarBinary(SecsVar):
    """Secs type for binary data."""

    formatCode = 0o10
    textCode = "B"
    preferredTypes = [bytes, bytearray]

    def __init__(self, value=None, count=-1):
        """
        Initialize a binary secs variable.

        :param value: initial value
        :type value: string/integer
        :param count: number of items this value
        :type count: integer
        """
        #super(SecsVarBinary, self).__init__()
        SecsVar.__init__(self)

        self.value = bytearray()
        self.count = count
        if value is not None:
            self.set(value)

    def __repr__(self):
        """Generate textual representation for an object of this class."""
        if len(self.value) == 0:
            return "<{}>".format(self.textCode)

        data = " ".join("0x{:x}".format(c) for c in self.value)

        return "<{} {}>".format(self.textCode, data.strip())

    def __len__(self):
        """Get the length."""
        return len(self.value)

    def __getitem__(self, key):
        """Get an item using the indexer operator."""
        if key >= self.count:
            raise IndexError("Index {} out of bounds ({})".format(key, self.count))

        if key >= len(self.value):
            return 0

        return self.value[key]

    def __setitem__(self, key, item):
        """Set an item using the indexer operator."""
        if key >= self.count:
            raise IndexError("Index {} out of bounds ({})".format(key, self.count))

        if key >= len(self.value):
            while key >= len(self.value):
                self.value.append(0)

        self.value[key] = item

    def __eq__(self, other):
        """Check equality with other object."""
        if isinstance(other, SecsVarDynamic):
            return other.value.value == self.value

        if isinstance(other, SecsVar):
            return other.value == self.value

        return other == self.value

    def __hash__(self):
        """Get data item for hashing."""
        return hash(bytes(self.value))

    def __check_single_item_support(self, value):
        if isinstance(value, bool):
            return True

        if isinstance(value, int):
            if 0 <= value <= 255:
                return True
            return False

        return False

    def supports_value(self, value):
        """
        Check if the current instance supports the provided value.

        :param value: value to test
        :type value: any
        """
        if isinstance(value, (list, tuple)):
            if self.count > 0 and len(value) > self.count:
                return False
            for item in value:
                if not self.__check_single_item_support(item):
                    return False

            return True

        if isinstance(value, bytearray):
            if self.count > 0 and len(value) > self.count:
                return False
            return True

        if isinstance(value, bytes):
            if self.count > 0 and len(value) > self.count:
                return False
            return True

        if isinstance(value, str):
            if self.count > 0 and len(value) > self.count:
                return False
            try:
                value.encode('ascii')
            except UnicodeEncodeError:
                return False

            return True

        return self.__check_single_item_support(value)

    def set(self, value):
        """
        Set the internal value to the provided value.

        :param value: new value
        :type value: string/integer
        """
        if value is None:
            return

        if isinstance(value, bytes):
            value = bytearray(value)
        elif isinstance(value, str):
            value = bytearray(value.encode('ascii'))
        elif isinstance(value, (list, tuple)):
            value = bytearray(value)
        elif isinstance(value, bytearray):
            pass
        elif isinstance(value, int):
            if 0 <= value <= 255:
                value = bytearray([value])
            else:
                raise ValueError("Value {} of type {} is out of range for {}".format(value, type(value).__name__,
                                                                                     self.__class__.__name__))
        else:
            raise TypeError("Unsupported type {} for {}".format(type(value).__name__, self.__class__.__name__))

        if 0 < self.count < len(value):
            raise ValueError("Value longer than {} chars ({} chars)".format(self.count, len(value)))

        self.value = value

    def get(self):
        """
        Return the internal value.

        :returns: internal value
        :rtype: list/integer
        """
        if len(self.value) == 1:
            return self.value[0]

        return bytes(self.value)

    def encode(self):
        """
        Encode the value to secs data.

        :returns: encoded data bytes
        :rtype: string
        """
        result = self.encode_item_header(len(self.value) if self.value is not None else 0)

        if self.value is not None:
            result += bytes(self.value)

        return result

    def decode(self, data, start=0):
        """
        Decode the secs byte data to the value.

        :param data: encoded data bytes
        :type data: string
        :param start: start position of value the data
        :type start: integer
        :returns: new start position
        :rtype: integer
        """
        (text_pos, _, length) = self.decode_item_header(data, start)

        # string
        result = None

        if length > 0:
            result = data[text_pos:text_pos + length]

        self.set(result)

        return text_pos + length


class SecsVarBoolean(SecsVar):
    """Secs type for boolean data."""

    formatCode = 0o11
    textCode = "BOOLEAN"
    preferredTypes = [bool]

    _trueStrings = ["TRUE", "YES"]
    _falseStrings = ["FALSE", "NO"]

    def __init__(self, value=None, count=-1):
        """
        Initialize a boolean secs variable.

        :param value: initial value
        :type value: list/boolean
        :param count: number of items this value
        :type count: integer
        """
        #super(SecsVarBoolean, self).__init__()
        SecsVar.__init__(self)

        self.value = []
        self.count = count
        if value is not None:
            self.set(value)

    def __repr__(self):
        """Generate textual representation for an object of this class."""
        if len(self.value) == 0:
            return "<{}>".format(self.textCode)

        data = ""

        for boolean in self.value:
            data += "{} ".format(boolean)

        return "<{} {}>".format(self.textCode, data)

    def __len__(self):
        """Get the length."""
        return len(self.value)

    def __getitem__(self, key):
        """Get an item using the indexer operator."""
        return self.value[key]

    def __setitem__(self, key, item):
        """Set an item using the indexer operator."""
        self.value[key] = item

    def __eq__(self, other):
        """Check equality with other object."""
        if isinstance(other, SecsVarDynamic):
            return other.value.value == self.value
        if isinstance(other, SecsVar):
            return other.value == self.value
        if isinstance(other, list):
            return other == self.value
        return [other] == self.value

    def __hash__(self):
        """Get data item for hashing."""
        return hash(str(self.value))

    def __check_single_item_support(self, value):
        if isinstance(value, bool):
            return True

        if isinstance(value, int):
            if 0 <= value <= 1:
                return True
            return False

        if isinstance(value, str):
            if value.upper() in self._trueStrings or value.upper() in self._falseStrings:
                return True

            return False

        return False

    def supports_value(self, value):
        """
        Check if the current instance supports the provided value.

        :param value: value to test
        :type value: any
        """
        if isinstance(value, (list, tuple)):
            if 0 < self.count < len(value):
                return False
            for item in value:
                if not self.__check_single_item_support(item):
                    return False

            return True

        if isinstance(value, bytearray):
            if 0 < self.count < len(value):
                return False
            for char in value:
                if not 0 <= char <= 1:
                    return False
            return True

        return self.__check_single_item_support(value)

    def __convert_single_item(self, value):
        if isinstance(value, bool):
            return value

        if isinstance(value, int):
            if not 0 <= value <= 1:
                raise ValueError("Value {} out of bounds".format(value))

            return bool(value)

        if isinstance(value, str):
            if value.upper() in self._trueStrings:
                return True

            if value.upper() in self._falseStrings:
                return False

            raise ValueError("Value {} out of bounds".format(value))

        raise ValueError("Can't convert value {}".format(value))

    def set(self, value):
        """
        Set the internal value to the provided value.

        :param value: new value
        :type value: list/boolean
        """
        if isinstance(value, (list, tuple)):
            if 0 <= self.count < len(value):
                raise ValueError("Value longer than {} chars".format(self.count))

            new_value = []
            for item in value:
                new_value.append(self.__convert_single_item(item))

            self.value = new_value
        elif isinstance(value, bytearray):
            if 0 <= self.count < len(value):
                raise ValueError("Value longer than {} chars".format(self.count))

            new_value = []
            for char in value:
                if not 0 <= char <= 1:
                    raise ValueError("Value {} out of bounds".format(char))

                new_value.append(char)

            self.value = new_value
        else:
            self.value = [self.__convert_single_item(value)]

    def get(self):
        """
        Return the internal value.

        :returns: internal value
        :rtype: list/boolean
        """
        if len(self.value) == 1:
            return self.value[0]

        return self.value

    def encode(self):
        """
        Encode the value to secs data.

        :returns: encoded data bytes
        :rtype: string
        """
        result = self.encode_item_header(len(self.value))

        for counter in range(len(self.value)):
            value = self.value[counter]
            if value:
                result += b"\1"
            else:
                result += b"\0"

        return result

    def decode(self, data, start=0):
        """
        Decode the secs byte data to the value.

        :param data: encoded data bytes
        :type data: string
        :param start: start position of value the data
        :type start: integer
        :returns: new start position
        :rtype: integer
        """
        (text_pos, _, length) = self.decode_item_header(data, start)

        result = []

        for _ in range(length):
            if bytearray(data)[text_pos] == 0:
                result.append(False)
            else:
                result.append(True)

            text_pos += 1

        self.set(result)

        return text_pos


class SecsVarText(SecsVar):
    """Secs type base for any text data."""

    formatCode = -1
    textCode = u""
    #controlChars = u"".join(chr(ch) for ch in range(256) if unicodedata.category(chr(ch))[0] == "C")
    controlChars = u'\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f\x7f\x80\x81\x82\x83\x84\x85\x86\x87\x88\x89\x8a\x8b\x8c\x8d\x8e\x8f\x90\x91\x92\x93\x94\x95\x96\x97\x98\x99\x9a\x9b\x9c\x9d\x9e\x9f\xad'
    coding = ""

    def __init__(self, value="", count=-1):
        """
        Initialize a secs text variable.

        :param value: initial value
        :type value: string
        :param count: number of items this value
        :type count: integer
        """
        #super(SecsVarText, self).__init__()
        SecsVar.__init__(self)

        self.value = u""
        self.count = count

        if value is not None:
            self.set(value)

    def __repr__(self):
        """Generate textual representation for an object of this class."""
        if len(self.value) == 0:
            return u"<{}>".format(self.textCode)

        data = u""
        last_char_printable = False

        for char in self.value:
            output = char

            if char not in self.controlChars:
                if last_char_printable:
                    data += output
                else:
                    data += ' "' + output
                last_char_printable = True
            else:
                if last_char_printable:
                    data += '" ' + hex(ord(output))
                else:
                    data += ' ' + hex(ord(output))
                last_char_printable = False

        if last_char_printable:
            data += '"'

        return u"<{}{}>".format(self.textCode, data)

    def __len__(self):
        """Get the length."""
        return len(self.value)

    def __eq__(self, other):
        """Check equality with other object."""
        if isinstance(other, SecsVarDynamic):
            return other.value.value == self.value

        if isinstance(other, SecsVar):
            return other.value == self.value

        return other == self.value

    def __hash__(self):
        """Get data item for hashing."""
        return hash(self.value)

    def __check_single_item_support(self, value):
        if isinstance(value, bool):
            return True

        if isinstance(value, int):
            if 0 <= value <= 255:
                return True
            return False

        return False

    def __supports_value_listtypes(self, value):
        if self.count > 0 and len(value) > self.count:
            return False
        for item in value:
            if not self.__check_single_item_support(item):
                return False

        return True

    def supports_value(self, value):
        """
        Check if the current instance supports the provided value.

        :param value: value to test
        :type value: any
        """
        if isinstance(value, (list, tuple, bytearray)):
            return self.__supports_value_listtypes(value)

        if isinstance(value, bytes):
            if 0 < self.count < len(value):
                return False
            return True

        if isinstance(value, (int, float, complex)):
            if 0 < self.count < len(str(value)):
                return False
            return True

        if isinstance(value, str):
            if 0 < self.count < len(value):
                return False
            try:
                value.encode(self.coding)
            except UnicodeEncodeError:
                return False

            return True

        return None

    def set(self, value):
        """
        Set the internal value to the provided value.

        :param value: new value
        :type value: string/integer
        """
        if value is None:
            raise ValueError("{} can't be None".format(self.__class__.__name__))

        if isinstance(value, bytes):
            value = value.decode(self.coding)
        elif isinstance(value, bytearray):
            value = bytes(value).decode(self.coding)
        elif isinstance(value, (list, tuple)):
            value = str(bytes(bytearray(value)).decode(self.coding))
        elif isinstance(value, (int, float, complex)):
            value = str(value)
        elif isinstance(value, str):
            value.encode(self.coding)  # try if it can be encoded as ascii (values 0-127)
        elif isinstance(value, unicode):
            value = str(value)
        else:
            raise TypeError("Unsupported type {} for {}".format(type(value).__name__, self.__class__.__name__))

        if 0 < self.count < len(value):
            raise ValueError("Value longer than {} chars ({} chars)".format(self.count, len(value)))

        self.value = str(value)

    def get(self):
        """
        Return the internal value.

        :returns: internal value
        :rtype: string
        """
        return self.value

    def encode(self):
        """
        Encode the value to secs data.

        :returns: encoded data bytes
        :rtype: string
        """
        result = self.encode_item_header(len(self.value))

        result += self.value.encode(self.coding)

        return result

    def decode(self, data, start=0):
        """
        Decode the secs byte data to the value.

        :param data: encoded data bytes
        :type data: string
        :param start: start position of value the data
        :type start: integer
        :returns: new start position
        :rtype: integer
        """
        (text_pos, _, length) = self.decode_item_header(data, start)

        # string
        result = u""

        if length > 0:
            result = data[text_pos:text_pos + length].decode(self.coding)

        self.set(result)

        return text_pos + length


class SecsVarString(SecsVarText):
    """
    Secs type for string data.

    :param value: initial value
    :type value: string
    :param count: number of items this value
    :type count: integer
    """

    formatCode = 0o20
    textCode = u"A"
    preferredTypes = [bytes, str]
    #controlChars = u"".join(chr(ch) for ch in range(256) if unicodedata.category(chr(ch))[0] == "C" or ch > 127)
    controlChars = u'\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f\x7f\x80\x81\x82\x83\x84\x85\x86\x87\x88\x89\x8a\x8b\x8c\x8d\x8e\x8f\x90\x91\x92\x93\x94\x95\x96\x97\x98\x99\x9a\x9b\x9c\x9d\x9e\x9f\xa0\xa1\xa2\xa3\xa4\xa5\xa6\xa7\xa8\xa9\xaa\xab\xac\xad\xae\xaf\xb0\xb1\xb2\xb3\xb4\xb5\xb6\xb7\xb8\xb9\xba\xbb\xbc\xbd\xbe\xbf\xc0\xc1\xc2\xc3\xc4\xc5\xc6\xc7\xc8\xc9\xca\xcb\xcc\xcd\xce\xcf\xd0\xd1\xd2\xd3\xd4\xd5\xd6\xd7\xd8\xd9\xda\xdb\xdc\xdd\xde\xdf\xe0\xe1\xe2\xe3\xe4\xe5\xe6\xe7\xe8\xe9\xea\xeb\xec\xed\xee\xef\xf0\xf1\xf2\xf3\xf4\xf5\xf6\xf7\xf8\xf9\xfa\xfb\xfc\xfd\xfe\xff'
    coding = "latin-1"


class SecsVarJIS8(SecsVarText):
    """
    Secs type for string data.

    :param value: initial value
    :type value: string
    :param count: number of items this value
    :type count: integer
    """

    formatCode = 0o21
    textCode = u"J"
    preferredTypes = [bytes, str]
    #controlChars = u"".join(chr(ch) for ch in range(256) if unicodedata.category(chr(ch))[0] == "C")
    controlChars = u'\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f\x7f\x80\x81\x82\x83\x84\x85\x86\x87\x88\x89\x8a\x8b\x8c\x8d\x8e\x8f\x90\x91\x92\x93\x94\x95\x96\x97\x98\x99\x9a\x9b\x9c\x9d\x9e\x9f\xad'
    coding = "jis-8"


class SecsVarNumber(SecsVar):
    """Secs base type for numeric data."""

    formatCode = 0
    textCode = ""
    _basetype = int
    _min = 0
    _max = 0
    _bytes = 0
    _structCode = ""

    def __init__(self, value=None, count=-1):
        """
        Initialize a numeric secs variable.

        :param value: initial value
        :type value: list/integer/float
        :param count: number of items this value
        :type count: integer
        """
        #super(SecsVarNumber, self).__init__()
        SecsVar.__init__(self)

        self.value = []
        self.count = count
        if value is not None:
            self.set(value)

    def __repr__(self):
        """Generate textual representation for an object of this class."""
        if len(self.value) == 0:
            return "<{}>".format(self.textCode)

        data = ""

        for item in self.value:
            data += "{} ".format(item)

        return "<{} {}>".format(self.textCode, data)

    def __len__(self):
        """Get the length."""
        return len(self.value)

    def __getitem__(self, key):
        """Get an item using the indexer operator."""
        return self.value[key]

    def __setitem__(self, key, item):
        """Set an item using the indexer operator."""
        self.value[key] = item

    def __eq__(self, other):
        """Check equality with other object."""
        if isinstance(other, SecsVarDynamic):
            return other.value.value == self.value
        if isinstance(other, SecsVar):
            return other.value == self.value
        if isinstance(other, list):
            return other == self.value
        return [other] == self.value

    def __hash__(self):
        """Get data item for hashing."""
        return hash(str(self.value))

    def __check_single_item_support(self, value):
        if isinstance(value, float) and self._basetype == int:
            return False

        if isinstance(value, bool):
            return True

        if isinstance(value, (int, float)):
            if value < self._min or value > self._max:
                return False
            return True

        if isinstance(value, (bytes, str)):
            try:
                val = self._basetype(value)
            except ValueError:
                return False
            if val < self._min or val > self._max:
                return False
            return True
        return False

    def supports_value(self, value):
        """
        Check if the current instance supports the provided value.

        :param value: value to test
        :type value: any
        """
        if isinstance(value, (list, tuple)):
            if 0 <= self.count < len(value):
                return False
            for item in value:
                if not self.__check_single_item_support(item):
                    return False
            return True
        if isinstance(value, bytearray):
            if 0 <= self.count < len(value):
                return False
            for item in value:
                if item < self._min or item > self._max:
                    return False
            return True
        return self.__check_single_item_support(value)

    def set(self, value):
        """
        Set the internal value to the provided value.

        :param value: new value
        :type value: list/integer/float
        """
        if isinstance(value, float) and self._basetype == int:
            raise ValueError("Invalid value {}".format(value))

        if isinstance(value, (list, tuple)):
            if 0 <= self.count < len(value):
                raise ValueError("Value longer than {} chars".format(self.count))

            new_list = []
            for item in value:
                item = self._basetype(item)
                if item < self._min or item > self._max:
                    raise ValueError("Invalid value {}".format(item))

                new_list.append(item)
            self.value = new_list
        elif isinstance(value, bytearray):
            if 0 <= self.count < len(value):
                raise ValueError("Value longer than {} chars".format(self.count))

            new_list = []
            for item in value:
                if item < self._min or item > self._max:
                    raise ValueError("Invalid value {}".format(item))
                new_list.append(item)
            self.value = new_list
        else:
            new_value = self._basetype(value)

            if new_value < self._min or new_value > self._max:
                raise ValueError("Invalid value {}".format(value))

            self.value = [new_value]

    def get(self):
        """
        Return the internal value.

        :returns: internal value
        :rtype: list/integer/float
        """
        if len(self.value) == 1:
            return self.value[0]

        return self.value

    def encode(self):
        """
        Encode the value to secs data.

        :returns: encoded data bytes
        :rtype: string
        """
        result = self.encode_item_header(len(self.value) * self._bytes)

        for counter in range(len(self.value)):
            value = self.value[counter]
            result += struct.pack(">{}".format(self._structCode), value)

        return result

    def decode(self, data, start=0):
        """
        Decode the secs byte data to the value.

        :param data: encoded data bytes
        :type data: string
        :param start: start position of value the data
        :type start: integer
        :returns: new start position
        :rtype: integer
        """
        (text_pos, _, length) = self.decode_item_header(data, start)

        result = []

        for _ in range(length // self._bytes):
            result_text = data[text_pos:text_pos + self._bytes]

            if len(result_text) != self._bytes:
                raise ValueError(
                    "No enough data found for {} with length {} at position {} ".format(
                        self.__class__.__name__,
                        length,
                        start))

            result.append(struct.unpack(">{}".format(self._structCode), result_text)[0])

            text_pos += self._bytes

        self.set(result)

        return text_pos


class SecsVarI8(SecsVarNumber):
    """
    Secs type for 8 byte signed data.

    :param value: initial value
    :type value: list/integer
    :param count: number of items this value
    :type count: integer
    """

    formatCode = 0o30
    textCode = "I8"
    _basetype = int
    _min = -9223372036854775808
    _max = 9223372036854775807
    _bytes = 8
    _structCode = "q"
    preferredTypes = [int]


class SecsVarI1(SecsVarNumber):
    """
    Secs type for 1 byte signed data.

    :param value: initial value
    :type value: list/integer
    :param count: number of items this value
    :type count: integer
    """

    formatCode = 0o31
    textCode = "I1"
    _basetype = int
    _min = -128
    _max = 127
    _bytes = 1
    _structCode = "b"
    preferredTypes = [int]


class SecsVarI2(SecsVarNumber):
    """
    Secs type for 2 byte signed data.

    :param value: initial value
    :type value: list/integer
    :param count: number of items this value
    :type count: integer
    """

    formatCode = 0o32
    textCode = "I2"
    _basetype = int
    _min = -32768
    _max = 32767
    _bytes = 2
    _structCode = "h"
    preferredTypes = [int]


class SecsVarI4(SecsVarNumber):
    """
    Secs type for 4 byte signed data.

    :param value: initial value
    :type value: list/integer
    :param count: number of items this value
    :type count: integer
    """

    formatCode = 0o34
    textCode = "I4"
    _basetype = int
    _min = -2147483648
    _max = 2147483647
    _bytes = 4
    _structCode = "l"
    preferredTypes = [int]


class SecsVarF8(SecsVarNumber):
    """
    Secs type for 8 byte float data.

    :param value: initial value
    :type value: list/float
    :param count: number of items this value
    :type count: integer
    """

    formatCode = 0o40
    textCode = "F8"
    _basetype = float
    _min = -1.79769e+308
    _max = 1.79769e+308
    _bytes = 8
    _structCode = "d"
    preferredTypes = [float]


class SecsVarF4(SecsVarNumber):
    """
    Secs type for 4 byte float data.

    :param value: initial value
    :type value: list/float
    :param count: number of items this value
    :type count: integer
    """

    formatCode = 0o44
    textCode = "F4"
    _basetype = float
    _min = -3.40282e+38
    _max = 3.40282e+38
    _bytes = 4
    _structCode = "f"
    preferredTypes = [float]


class SecsVarU8(SecsVarNumber):
    """
    Secs type for 8 byte unsigned data.

    :param value: initial value
    :type value: list/integer
    :param count: number of items this value
    :type count: integer
    """

    formatCode = 0o50
    textCode = "U8"
    _basetype = int
    _min = 0
    _max = 18446744073709551615
    _bytes = 8
    _structCode = "Q"
    preferredTypes = [int]


class SecsVarU1(SecsVarNumber):
    """
    Secs type for 1 byte unsigned data.

    :param value: initial value
    :type value: list/integer
    :param count: number of items this value
    :type count: integer
    """

    formatCode = 0o51
    textCode = "U1"
    _basetype = int
    _min = 0
    _max = 255
    _bytes = 1
    _structCode = "B"
    preferredTypes = [int]


class SecsVarU2(SecsVarNumber):
    """
    Secs type for 2 byte unsigned data.

    :param value: initial value
    :type value: list/integer
    :param count: number of items this value
    :type count: integer
    """

    formatCode = 0o52
    textCode = "U2"
    _basetype = int
    _min = 0
    _max = 65535
    _bytes = 2
    _structCode = "H"
    preferredTypes = [int]


class SecsVarU4(SecsVarNumber):
    """
    Secs type for 4 byte unsigned data.

    :param value: initial value
    :type value: list/integer
    :param count: number of items this value
    :type count: integer
    """

    formatCode = 0o54
    textCode = "U4"
    _basetype = int
    _min = 0
    _max = 4294967295
    _bytes = 4
    _structCode = "L"
    preferredTypes = [int]


# DataItemMeta adds __type__ member as base class
class DataItemMeta(type):
    """Meta class for data items."""

    def __new__(mcs, name, bases, attrs):
        if name != "DataItemBase":
            bases += (attrs["__type__"], )
        return type.__new__(mcs, name, bases, attrs)


# DataItemBase initializes __type__ member as base class and provides get_format
@add_metaclass(DataItemMeta)
class DataItemBase():
    """
    Base class for data items.

    It provides type and output handling.
    """

    __type__ = None
    __allowedtypes__ = None
    __count__ = -1

    def __repr__(self):
        return self.__type__.__repr__(self)

    def __init__(self, value=None):
        """
        Initialize a data item.

        :param value: Value of the data item
        """
        self.name = self.__class__.__name__

        if self.__type__ is SecsVarDynamic:
            self.__type__.__init__(self, self.__allowedtypes__, value, self.__count__)
        else:
            self.__type__.__init__(self, value, self.__count__)

    @classmethod
    def get_format(cls, showname=True):
        """
        Format the contents as a string.

        :param showname: Display the real class name when True
        :return: Formatted value string
        """
        if showname:
            clsname = format(cls.__name__)
        else:
            clsname = "DATA"

        if cls.__type__ is SecsVarDynamic:
            if cls.__count__ > 0:
                return "{}: {}[{}]".format(clsname, "/".join([x.textCode for x in cls.__allowedtypes__]), cls.__count__)

            return "{}: {}".format(clsname, "/".join([x.textCode for x in cls.__allowedtypes__]))

        if cls.__count__ > 0:
            return "{}: {}[{}]".format(clsname, cls.textCode, cls.__count__)

        return "{}: {}".format(clsname, cls.textCode)


class ACKC5(DataItemBase):
    """
    Acknowledge code.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+-------------------+------------------------------------------------+
        | Value | Description       | Constant                                       |
        +=======+===================+================================================+
        | 0     | Accepted          | :const:`secsgem.secs.dataitems.ACKC5.ACCEPTED` |
        +-------+-------------------+------------------------------------------------+
        | 1-63  | Error             | :const:`secsgem.secs.dataitems.ACKC5.ERROR`    |
        +-------+-------------------+------------------------------------------------+

    **Used In Function**
        - :class:`SecsS05F02 <secsgem.secs.functions.SecsS05F02>`
        - :class:`SecsS05F04 <secsgem.secs.functions.SecsS05F04>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACCEPTED = 0
    ERROR = 1


class ACKC6(DataItemBase):
    """
    Acknowledge code.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+-------------------+------------------------------------------------+
        | Value | Description       | Constant                                       |
        +=======+===================+================================================+
        | 0     | Accepted          | :const:`secsgem.secs.dataitems.ACKC6.ACCEPTED` |
        +-------+-------------------+------------------------------------------------+
        | 1-63  | Error             | :const:`secsgem.secs.dataitems.ACKC6.ERROR`    |
        +-------+-------------------+------------------------------------------------+

    **Used In Function**
        - :class:`SecsS06F02 <secsgem.secs.functions.SecsS06F02>`
        - :class:`SecsS06F04 <secsgem.secs.functions.SecsS06F04>`
        - :class:`SecsS06F10 <secsgem.secs.functions.SecsS06F10>`
        - :class:`SecsS06F12 <secsgem.secs.functions.SecsS06F12>`
        - :class:`SecsS06F14 <secsgem.secs.functions.SecsS06F14>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACCEPTED = 0
    ERROR = 1


class ACKC7(DataItemBase):
    """
    Acknowledge code.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+------------------------+--------------------------------------------------------+
        | Value | Description            | Constant                                               |
        +=======+========================+========================================================+
        | 0     | Accepted               | :const:`secsgem.secs.dataitems.ACKC7.ACCEPTED`         |
        +-------+------------------------+--------------------------------------------------------+
        | 1     | Permission not granted | :const:`secsgem.secs.dataitems.ACKC7.NO_PERMISSION`    |
        +-------+------------------------+--------------------------------------------------------+
        | 2     | Length error           | :const:`secsgem.secs.dataitems.ACKC7.LENGTH_ERROR`     |
        +-------+------------------------+--------------------------------------------------------+
        | 3     | Matrix overflow        | :const:`secsgem.secs.dataitems.ACKC7.MATRIX_OVERFLOW`  |
        +-------+------------------------+--------------------------------------------------------+
        | 4     | PPID not found         | :const:`secsgem.secs.dataitems.ACKC7.PPID_NOT_FOUND`   |
        +-------+------------------------+--------------------------------------------------------+
        | 5     | Mode unsupported       | :const:`secsgem.secs.dataitems.ACKC7.MODE_UNSUPPORTED` |
        +-------+------------------------+--------------------------------------------------------+
        | 6     | Performed later        | :const:`secsgem.secs.dataitems.ACKC7.PERFORMED_LATER`  |
        +-------+------------------------+--------------------------------------------------------+
        | 7-63  | Reserved               |                                                        |
        +-------+------------------------+--------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS07F04 <secsgem.secs.functions.SecsS07F04>`
        - :class:`SecsS07F12 <secsgem.secs.functions.SecsS07F12>`
        - :class:`SecsS07F14 <secsgem.secs.functions.SecsS07F14>`
        - :class:`SecsS07F16 <secsgem.secs.functions.SecsS07F16>`
        - :class:`SecsS07F18 <secsgem.secs.functions.SecsS07F18>`
        - :class:`SecsS07F24 <secsgem.secs.functions.SecsS07F24>`
        - :class:`SecsS07F32 <secsgem.secs.functions.SecsS07F32>`
        - :class:`SecsS07F38 <secsgem.secs.functions.SecsS07F38>`
        - :class:`SecsS07F40 <secsgem.secs.functions.SecsS07F40>`
        - :class:`SecsS07F42 <secsgem.secs.functions.SecsS07F42>`
        - :class:`SecsS07F44 <secsgem.secs.functions.SecsS07F44>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACCEPTED = 0
    NO_PERMISSION = 1
    LENGTH_ERROR = 2
    MATRIX_OVERFLOW = 3
    PPID_NOT_FOUND = 4
    MODE_UNSUPPORTED = 5
    PERFORMED_LATER = 6


class ACKC10(DataItemBase):
    """
    Acknowledge code.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+------------------------+---------------------------------------------------------------+
        | Value | Description            | Constant                                                      |
        +=======+========================+===============================================================+
        | 0     | Accepted               | :const:`secsgem.secs.dataitems.ACKC10.ACCEPTED`               |
        +-------+------------------------+---------------------------------------------------------------+
        | 1     | Will not be displayed  | :const:`secsgem.secs.dataitems.ACKC10.NOT_DISPLAYED`          |
        +-------+------------------------+---------------------------------------------------------------+
        | 2     | Terminal not available | :const:`secsgem.secs.dataitems.ACKC10.TERMINAL_NOT_AVAILABLE` |
        +-------+------------------------+---------------------------------------------------------------+
        | 3-63  | Other error            |                                                               |
        +-------+------------------------+---------------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS10F02 <secsgem.secs.functions.SecsS10F02>`
        - :class:`SecsS10F04 <secsgem.secs.functions.SecsS10F04>`
        - :class:`SecsS10F06 <secsgem.secs.functions.SecsS10F06>`
        - :class:`SecsS10F10 <secsgem.secs.functions.SecsS10F10>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACCEPTED = 0
    NOT_DISPLAYED = 1
    TERMINAL_NOT_AVAILABLE = 2


class ACKA(DataItemBase):
    """
    Request success.

       :Types: :class:`SecsVarBoolean <secsgem.secs.variables.SecsVarBoolean>`
       :Length: 1

    **Values**
        +-------+---------+
        | Value |         |
        +=======+=========+
        | True  | Success |
        +-------+---------+
        | False | Failed  |
        +-------+---------+

    **Used In Function**
        - :class:`SecsS05F14 <secsgem.secs.functions.SecsS05F14>`
        - :class:`SecsS05F15 <secsgem.secs.functions.SecsS05F15>`
        - :class:`SecsS05F18 <secsgem.secs.functions.SecsS05F18>`
        - :class:`SecsS16F02 <secsgem.secs.functions.SecsS16F02>`
        - :class:`SecsS16F04 <secsgem.secs.functions.SecsS16F04>`
        - :class:`SecsS16F06 <secsgem.secs.functions.SecsS16F06>`
        - :class:`SecsS16F12 <secsgem.secs.functions.SecsS16F12>`
        - :class:`SecsS16F14 <secsgem.secs.functions.SecsS16F14>`
        - :class:`SecsS16F16 <secsgem.secs.functions.SecsS16F16>`
        - :class:`SecsS16F18 <secsgem.secs.functions.SecsS16F18>`
        - :class:`SecsS16F24 <secsgem.secs.functions.SecsS16F24>`
        - :class:`SecsS16F26 <secsgem.secs.functions.SecsS16F26>`
        - :class:`SecsS16F28 <secsgem.secs.functions.SecsS16F28>`
        - :class:`SecsS16F30 <secsgem.secs.functions.SecsS16F30>`
        - :class:`SecsS17F04 <secsgem.secs.functions.SecsS17F04>`
        - :class:`SecsS17F08 <secsgem.secs.functions.SecsS17F08>`
        - :class:`SecsS17F14 <secsgem.secs.functions.SecsS17F14>`

    """

    __type__ = SecsVarBoolean
    __count__ = 1


class ALCD(DataItemBase):
    """
    Alarm code byte.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+---------------------------+----------------------------------------------------------------+
        | Value | Description               | Constant                                                       |
        +=======+===========================+================================================================+
        | 0     | Not used                  |                                                                |
        +-------+---------------------------+----------------------------------------------------------------+
        | 1     | Personal safety           | :const:`secsgem.secs.dataitems.ALCD.PERSONAL_SAFETY`           |
        +-------+---------------------------+----------------------------------------------------------------+
        | 2     | Equipment safety          | :const:`secsgem.secs.dataitems.ALCD.EQUIPMENT_SAFETY`          |
        +-------+---------------------------+----------------------------------------------------------------+
        | 3     | Parameter control warning | :const:`secsgem.secs.dataitems.ALCD.PARAMETER_CONTROL_WARNING` |
        +-------+---------------------------+----------------------------------------------------------------+
        | 4     | Parameter control error   | :const:`secsgem.secs.dataitems.ALCD.PARAMETER_CONTROL_ERROR`   |
        +-------+---------------------------+----------------------------------------------------------------+
        | 5     | Irrecoverable error       | :const:`secsgem.secs.dataitems.ALCD.IRRECOVERABLE_ERROR`       |
        +-------+---------------------------+----------------------------------------------------------------+
        | 6     | Equipment status warning  | :const:`secsgem.secs.dataitems.ALCD.EQUIPMENT_STATUS_WARNING`  |
        +-------+---------------------------+----------------------------------------------------------------+
        | 7     | Attention flags           | :const:`secsgem.secs.dataitems.ALCD.ATTENTION_FLAGS`           |
        +-------+---------------------------+----------------------------------------------------------------+
        | 8     | Data integrity            | :const:`secsgem.secs.dataitems.ALCD.DATA_INTEGRITY`            |
        +-------+---------------------------+----------------------------------------------------------------+
        | 9-63  | Other catogories          |                                                                |
        +-------+---------------------------+----------------------------------------------------------------+
        | 128   | Alarm set flag            | :const:`secsgem.secs.dataitems.ALCD.ALARM_SET`                 |
        +-------+---------------------------+----------------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS05F01 <secsgem.secs.functions.SecsS05F01>`
        - :class:`SecsS05F06 <secsgem.secs.functions.SecsS05F06>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    PERSONAL_SAFETY = 1
    EQUIPMENT_SAFETY = 2
    PARAMETER_CONTROL_WARNING = 3
    PARAMETER_CONTROL_ERROR = 4
    IRRECOVERABLE_ERROR = 5
    EQUIPMENT_STATUS_WARNING = 6
    ATTENTION_FLAGS = 7
    DATA_INTEGRITY = 8
    ALARM_SET = 128


class ALED(DataItemBase):
    """
    Alarm en-/disable code byte.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +---------+-------------+----------------------------------------------+
        | Value   | Description | Constant                                     |
        +=========+=============+==============================================+
        | 0       | Disable     | :const:`secsgem.secs.dataitems.ALED.DISABLE` |
        +---------+-------------+----------------------------------------------+
        | 1-127   | Not used    |                                              |
        +---------+-------------+----------------------------------------------+
        | 128     | Enable      | :const:`secsgem.secs.dataitems.ALED.ENABLE`  |
        +---------+-------------+----------------------------------------------+
        | 129-255 | Not used    |                                              |
        +---------+-------------+----------------------------------------------+

    **Used In Function**
        - :class:`SecsS05F03 <secsgem.secs.functions.SecsS05F03>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    DISABLE = 0
    ENABLE = 128


class ALID(DataItemBase):
    """
    Alarm ID.

    :Types:
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS05F01 <secsgem.secs.functions.SecsS05F01>`
        - :class:`SecsS05F03 <secsgem.secs.functions.SecsS05F03>`
        - :class:`SecsS05F05 <secsgem.secs.functions.SecsS05F05>`
        - :class:`SecsS05F06 <secsgem.secs.functions.SecsS05F06>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8]


class ALTX(DataItemBase):
    """
    Alarm ID.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS05F01 <secsgem.secs.functions.SecsS05F01>`
        - :class:`SecsS05F06 <secsgem.secs.functions.SecsS05F06>`

    """

    __type__ = SecsVarString
    __count__ = 120


class ATTRDATA(DataItemBase):
    """
    Object attribute value.

    :Types:
       - :class:`SecsVarArray <secsgem.secs.variables.SecsVarArray>`
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarBoolean <secsgem.secs.variables.SecsVarBoolean>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarF8 <secsgem.secs.variables.SecsVarF8>`
       - :class:`SecsVarF4 <secsgem.secs.variables.SecsVarF4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS01F20 <secsgem.secs.functions.SecsS01F20>`
        - :class:`SecsS03F17 <secsgem.secs.functions.SecsS03F17>`
        - :class:`SecsS03F18 <secsgem.secs.functions.SecsS03F18>`
        - :class:`SecsS13F14 <secsgem.secs.functions.SecsS13F14>`
        - :class:`SecsS13F16 <secsgem.secs.functions.SecsS13F16>`
        - :class:`SecsS14F01 <secsgem.secs.functions.SecsS14F01>`
        - :class:`SecsS14F02 <secsgem.secs.functions.SecsS14F02>`
        - :class:`SecsS14F03 <secsgem.secs.functions.SecsS14F03>`
        - :class:`SecsS14F04 <secsgem.secs.functions.SecsS14F04>`
        - :class:`SecsS14F09 <secsgem.secs.functions.SecsS14F09>`
        - :class:`SecsS14F10 <secsgem.secs.functions.SecsS14F10>`
        - :class:`SecsS14F11 <secsgem.secs.functions.SecsS14F11>`
        - :class:`SecsS14F12 <secsgem.secs.functions.SecsS14F12>`
        - :class:`SecsS14F13 <secsgem.secs.functions.SecsS14F13>`
        - :class:`SecsS14F14 <secsgem.secs.functions.SecsS14F14>`
        - :class:`SecsS14F15 <secsgem.secs.functions.SecsS14F15>`
        - :class:`SecsS14F16 <secsgem.secs.functions.SecsS14F16>`
        - :class:`SecsS14F17 <secsgem.secs.functions.SecsS14F17>`
        - :class:`SecsS14F18 <secsgem.secs.functions.SecsS14F18>`
        - :class:`SecsS18F02 <secsgem.secs.functions.SecsS18F02>`
        - :class:`SecsS18F03 <secsgem.secs.functions.SecsS18F03>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarArray, SecsVarBoolean, SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2,
                        SecsVarI4, SecsVarI8, SecsVarF4, SecsVarF8, SecsVarString, SecsVarBinary]


class ATTRID(DataItemBase):
    """
    Object attribute identifier.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS01F19 <secsgem.secs.functions.SecsS01F19>`
        - :class:`SecsS03F17 <secsgem.secs.functions.SecsS03F17>`
        - :class:`SecsS03F18 <secsgem.secs.functions.SecsS03F18>`
        - :class:`SecsS13F14 <secsgem.secs.functions.SecsS13F14>`
        - :class:`SecsS13F16 <secsgem.secs.functions.SecsS13F16>`
        - :class:`SecsS14F01 <secsgem.secs.functions.SecsS14F01>`
        - :class:`SecsS14F02 <secsgem.secs.functions.SecsS14F02>`
        - :class:`SecsS14F03 <secsgem.secs.functions.SecsS14F03>`
        - :class:`SecsS14F04 <secsgem.secs.functions.SecsS14F04>`
        - :class:`SecsS14F08 <secsgem.secs.functions.SecsS14F08>`
        - :class:`SecsS14F09 <secsgem.secs.functions.SecsS14F09>`
        - :class:`SecsS14F10 <secsgem.secs.functions.SecsS14F10>`
        - :class:`SecsS14F11 <secsgem.secs.functions.SecsS14F11>`
        - :class:`SecsS14F12 <secsgem.secs.functions.SecsS14F12>`
        - :class:`SecsS14F13 <secsgem.secs.functions.SecsS14F13>`
        - :class:`SecsS14F14 <secsgem.secs.functions.SecsS14F14>`
        - :class:`SecsS14F15 <secsgem.secs.functions.SecsS14F15>`
        - :class:`SecsS14F16 <secsgem.secs.functions.SecsS14F16>`
        - :class:`SecsS14F17 <secsgem.secs.functions.SecsS14F17>`
        - :class:`SecsS14F18 <secsgem.secs.functions.SecsS14F18>`
        - :class:`SecsS18F01 <secsgem.secs.functions.SecsS18F01>`
        - :class:`SecsS18F03 <secsgem.secs.functions.SecsS18F03>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarString]


class ATTRRELN(DataItemBase):
    """
    Attribute relation to attribute of object.

       :Types: :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`

    **Values**
        +-------+-----------------------+-----------------------------------------------------+
        | Value | Description           | Constant                                            |
        +=======+=======================+=====================================================+
        | 0     | Equal to              | :const:`secsgem.secs.dataitems.ATTRRELN.EQUAL`      |
        +-------+-----------------------+-----------------------------------------------------+
        | 1     | Not equal to          | :const:`secsgem.secs.dataitems.ATTRRELN.NOT_EQUAL`  |
        +-------+-----------------------+-----------------------------------------------------+
        | 2     | Less than             | :const:`secsgem.secs.dataitems.ATTRRELN.LESS`       |
        +-------+-----------------------+-----------------------------------------------------+
        | 3     | Less than or equal to | :const:`secsgem.secs.dataitems.ATTRRELN.LESS_EQUAL` |
        +-------+-----------------------+-----------------------------------------------------+
        | 4     | More than             | :const:`secsgem.secs.dataitems.ATTRRELN.MORE`       |
        +-------+-----------------------+-----------------------------------------------------+
        | 5     | More than or equal to | :const:`secsgem.secs.dataitems.ATTRRELN.MORE_EQUAL` |
        +-------+-----------------------+-----------------------------------------------------+
        | 6     | Value present         | :const:`secsgem.secs.dataitems.ATTRRELN.PRESENT`    |
        +-------+-----------------------+-----------------------------------------------------+
        | 7     | Value absent          | :const:`secsgem.secs.dataitems.ATTRRELN.ABSENT`     |
        +-------+-----------------------+-----------------------------------------------------+
        | 8-63  | Error                 |                                                     |
        +-------+-----------------------+-----------------------------------------------------+

    **Used In Function**
        - :class:`SecsS14F01 <secsgem.secs.functions.SecsS14F01>`

    """

    __type__ = SecsVarU1

    EQUAL = 0
    NOT_EQUAL = 1
    LESS = 2
    LESS_EQUAL = 3
    MORE = 4
    MORE_EQUAL = 5
    PRESENT = 6
    ABSENT = 7


class BCEQU(DataItemBase):
    """
    Bin code equivalents.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`

    **Used In Function**
        - :class:`SecsS12F03 <secsgem.secs.functions.SecsS12F03>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarString]


class BINLT(DataItemBase):
    """
    Bin list.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`

    **Used In Function**
        - :class:`SecsS12F07 <secsgem.secs.functions.SecsS12F07>`
        - :class:`SecsS12F09 <secsgem.secs.functions.SecsS12F09>`
        - :class:`SecsS12F11 <secsgem.secs.functions.SecsS12F11>`
        - :class:`SecsS12F14 <secsgem.secs.functions.SecsS12F14>`
        - :class:`SecsS12F16 <secsgem.secs.functions.SecsS12F16>`
        - :class:`SecsS12F18 <secsgem.secs.functions.SecsS12F18>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarString]


class CEED(DataItemBase):
    """
    Collection event or trace enable/disable code.

       :Types: :class:`SecsVarBoolean <secsgem.secs.variables.SecsVarBoolean>`
       :Length: 1

    **Values**
        +-------+---------+
        | Value | State   |
        +=======+=========+
        | True  | Enable  |
        +-------+---------+
        | False | Disable |
        +-------+---------+

    **Used In Function**
        - :class:`SecsS02F37 <secsgem.secs.functions.SecsS02F37>`
        - :class:`SecsS17F05 <secsgem.secs.functions.SecsS17F05>`

    """

    __type__ = SecsVarBoolean
    __count__ = 1


class CEID(DataItemBase):
    """
    Collection event ID.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F35 <secsgem.secs.functions.SecsS02F35>`
        - :class:`SecsS02F37 <secsgem.secs.functions.SecsS02F37>`
        - :class:`SecsS06F03 <secsgem.secs.functions.SecsS06F03>`
        - :class:`SecsS06F08 <secsgem.secs.functions.SecsS06F08>`
        - :class:`SecsS06F09 <secsgem.secs.functions.SecsS06F09>`
        - :class:`SecsS06F11 <secsgem.secs.functions.SecsS06F11>`
        - :class:`SecsS06F13 <secsgem.secs.functions.SecsS06F13>`
        - :class:`SecsS06F15 <secsgem.secs.functions.SecsS06F15>`
        - :class:`SecsS06F16 <secsgem.secs.functions.SecsS06F16>`
        - :class:`SecsS06F17 <secsgem.secs.functions.SecsS06F17>`
        - :class:`SecsS06F18 <secsgem.secs.functions.SecsS06F18>`
        - :class:`SecsS17F05 <secsgem.secs.functions.SecsS17F05>`
        - :class:`SecsS17F09 <secsgem.secs.functions.SecsS17F09>`
        - :class:`SecsS17F10 <secsgem.secs.functions.SecsS17F10>`
        - :class:`SecsS17F11 <secsgem.secs.functions.SecsS17F11>`
        - :class:`SecsS17F12 <secsgem.secs.functions.SecsS17F12>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString]


class COLCT(DataItemBase):
    """
    Column count in dies.

    :Types:
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8]


class COMMACK(DataItemBase):
    """
    Establish communications acknowledge.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+-------------------+--------------------------------------------------+
        | Value | Description       | Constant                                         |
        +=======+===================+==================================================+
        | 0     | Accepted          | :const:`secsgem.secs.dataitems.COMMACK.ACCEPTED` |
        +-------+-------------------+--------------------------------------------------+
        | 1     | Denied, Try Again | :const:`secsgem.secs.dataitems.COMMACK.DENIED`   |
        +-------+-------------------+--------------------------------------------------+
        | 2-63  | Reserved          |                                                  |
        +-------+-------------------+--------------------------------------------------+

    **Used In Function**
        - :class:`SecsS01F14 <secsgem.secs.functions.SecsS01F14>`
    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACCEPTED = 0
    DENIED = 1


class CPACK(DataItemBase):
    """
    Command parameter acknowledge code.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+------------------------+------------------------------------------------------------+
        | Value | Description            | Constant                                                   |
        +=======+========================+============================================================+
        | 1     | Parameter name unknown | :const:`secsgem.secs.dataitems.CPACK.PARAMETER_UNKNOWN`    |
        +-------+------------------------+------------------------------------------------------------+
        | 2     | CPVAL value illegal    | :const:`secsgem.secs.dataitems.CPACK.CPVAL_ILLEGAL_VALUE`  |
        +-------+------------------------+------------------------------------------------------------+
        | 3     | CPVAL format illegal   | :const:`secsgem.secs.dataitems.CPACK.CPVAL_ILLEGAL_FORMAT` |
        +-------+------------------------+------------------------------------------------------------+
        | 4-63  | Reserved               |                                                            |
        +-------+------------------------+------------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS02F42 <secsgem.secs.functions.SecsS02F42>`
    """

    __type__ = SecsVarBinary
    __count__ = 1

    PARAMETER_UNKNOWN = 1
    CPVAL_ILLEGAL_VALUE = 2
    CPVAL_ILLEGAL_FORMAT = 3


class CPNAME(DataItemBase):
    """
    Command parameter name.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F41 <secsgem.secs.functions.SecsS02F41>`
        - :class:`SecsS02F42 <secsgem.secs.functions.SecsS02F42>`
        - :class:`SecsS02F49 <secsgem.secs.functions.SecsS02F49>`
        - :class:`SecsS02F50 <secsgem.secs.functions.SecsS02F50>`
        - :class:`SecsS04F21 <secsgem.secs.functions.SecsS04F21>`
        - :class:`SecsS04F29 <secsgem.secs.functions.SecsS04F29>`
        - :class:`SecsS16F05 <secsgem.secs.functions.SecsS16F05>`
        - :class:`SecsS16F27 <secsgem.secs.functions.SecsS16F27>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString]


class CPVAL(DataItemBase):
    """
    Command parameter name.

    :Types:
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarBoolean <secsgem.secs.variables.SecsVarBoolean>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F41 <secsgem.secs.functions.SecsS02F41>`
        - :class:`SecsS02F49 <secsgem.secs.functions.SecsS02F49>`
        - :class:`SecsS04F21 <secsgem.secs.functions.SecsS04F21>`
        - :class:`SecsS04F29 <secsgem.secs.functions.SecsS04F29>`
        - :class:`SecsS16F05 <secsgem.secs.functions.SecsS16F05>`
        - :class:`SecsS16F27 <secsgem.secs.functions.SecsS16F27>`
        - :class:`SecsS18F13 <secsgem.secs.functions.SecsS18F13>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarBoolean, SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4,
                        SecsVarI8, SecsVarString, SecsVarBinary]


class DATAID(DataItemBase):
    """
    Data ID.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F33 <secsgem.secs.functions.SecsS02F33>`
        - :class:`SecsS02F35 <secsgem.secs.functions.SecsS02F35>`
        - :class:`SecsS02F39 <secsgem.secs.functions.SecsS02F39>`
        - :class:`SecsS02F45 <secsgem.secs.functions.SecsS02F45>`
        - :class:`SecsS02F49 <secsgem.secs.functions.SecsS02F49>`
        - :class:`SecsS03F15 <secsgem.secs.functions.SecsS03F15>`
        - :class:`SecsS03F17 <secsgem.secs.functions.SecsS03F17>`
        - :class:`SecsS04F19 <secsgem.secs.functions.SecsS04F19>`
        - :class:`SecsS04F25 <secsgem.secs.functions.SecsS04F25>`
        - :class:`SecsS06F03 <secsgem.secs.functions.SecsS06F03>`
        - :class:`SecsS06F05 <secsgem.secs.functions.SecsS06F05>`
        - :class:`SecsS06F07 <secsgem.secs.functions.SecsS06F07>`
        - :class:`SecsS06F08 <secsgem.secs.functions.SecsS06F08>`
        - :class:`SecsS06F09 <secsgem.secs.functions.SecsS06F09>`
        - :class:`SecsS06F11 <secsgem.secs.functions.SecsS06F11>`
        - :class:`SecsS06F13 <secsgem.secs.functions.SecsS06F13>`
        - :class:`SecsS06F16 <secsgem.secs.functions.SecsS06F16>`
        - :class:`SecsS06F18 <secsgem.secs.functions.SecsS06F18>`
        - :class:`SecsS06F27 <secsgem.secs.functions.SecsS06F27>`
        - :class:`SecsS13F11 <secsgem.secs.functions.SecsS13F11>`
        - :class:`SecsS13F13 <secsgem.secs.functions.SecsS13F13>`
        - :class:`SecsS13F15 <secsgem.secs.functions.SecsS13F15>`
        - :class:`SecsS14F19 <secsgem.secs.functions.SecsS14F19>`
        - :class:`SecsS14F21 <secsgem.secs.functions.SecsS14F21>`
        - :class:`SecsS14F23 <secsgem.secs.functions.SecsS14F23>`
        - :class:`SecsS15F27 <secsgem.secs.functions.SecsS15F27>`
        - :class:`SecsS15F29 <secsgem.secs.functions.SecsS15F29>`
        - :class:`SecsS15F33 <secsgem.secs.functions.SecsS15F33>`
        - :class:`SecsS15F35 <secsgem.secs.functions.SecsS15F35>`
        - :class:`SecsS15F37 <secsgem.secs.functions.SecsS15F37>`
        - :class:`SecsS15F39 <secsgem.secs.functions.SecsS15F39>`
        - :class:`SecsS15F41 <secsgem.secs.functions.SecsS15F41>`
        - :class:`SecsS15F43 <secsgem.secs.functions.SecsS15F43>`
        - :class:`SecsS15F45 <secsgem.secs.functions.SecsS15F45>`
        - :class:`SecsS15F47 <secsgem.secs.functions.SecsS15F47>`
        - :class:`SecsS15F49 <secsgem.secs.functions.SecsS15F49>`
        - :class:`SecsS16F01 <secsgem.secs.functions.SecsS16F01>`
        - :class:`SecsS16F03 <secsgem.secs.functions.SecsS16F03>`
        - :class:`SecsS16F05 <secsgem.secs.functions.SecsS16F05>`
        - :class:`SecsS16F11 <secsgem.secs.functions.SecsS16F11>`
        - :class:`SecsS16F13 <secsgem.secs.functions.SecsS16F13>`
        - :class:`SecsS17F01 <secsgem.secs.functions.SecsS17F01>`
        - :class:`SecsS17F05 <secsgem.secs.functions.SecsS17F05>`
        - :class:`SecsS17F09 <secsgem.secs.functions.SecsS17F09>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString]


class DATALENGTH(DataItemBase):
    """
    Length of data to be sent.

    :Types:
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F39 <secsgem.secs.functions.SecsS02F39>`
        - :class:`SecsS03F15 <secsgem.secs.functions.SecsS03F15>`
        - :class:`SecsS03F29 <secsgem.secs.functions.SecsS03F29>`
        - :class:`SecsS03F31 <secsgem.secs.functions.SecsS03F31>`
        - :class:`SecsS04F25 <secsgem.secs.functions.SecsS04F25>`
        - :class:`SecsS06F05 <secsgem.secs.functions.SecsS06F05>`
        - :class:`SecsS13F11 <secsgem.secs.functions.SecsS13F11>`
        - :class:`SecsS14F23 <secsgem.secs.functions.SecsS14F23>`
        - :class:`SecsS16F01 <secsgem.secs.functions.SecsS16F01>`
        - :class:`SecsS16F11 <secsgem.secs.functions.SecsS16F11>`
        - :class:`SecsS18F05 <secsgem.secs.functions.SecsS18F05>`
        - :class:`SecsS18F07 <secsgem.secs.functions.SecsS18F07>`
        - :class:`SecsS19F19 <secsgem.secs.functions.SecsS19F19>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8]


class DATLC(DataItemBase):
    """
    Data location.

       :Types: :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`

    **Used In Function**
        - :class:`SecsS12F19 <secsgem.secs.functions.SecsS12F19>`

    """

    __type__ = SecsVarU1


class DRACK(DataItemBase):
    """
    Define report acknowledge code.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+-------------------------------+----------------------------------------------------------+
        | Value | Description                   | Constant                                                 |
        +=======+===============================+==========================================================+
        | 0     | Acknowledge                   | :const:`secsgem.secs.dataitems.DRACK.ACK`                |
        +-------+-------------------------------+----------------------------------------------------------+
        | 1     | Denied, insufficient space    | :const:`secsgem.secs.dataitems.DRACK.INSUFFICIENT_SPACE` |
        +-------+-------------------------------+----------------------------------------------------------+
        | 2     | Denied, invalid format        | :const:`secsgem.secs.dataitems.DRACK.INVALID_FORMAT`     |
        +-------+-------------------------------+----------------------------------------------------------+
        | 3     | Denied, RPTID already defined | :const:`secsgem.secs.dataitems.DRACK.RPTID_REDEFINED`    |
        +-------+-------------------------------+----------------------------------------------------------+
        | 4     | Denied, VID doesn't exist     | :const:`secsgem.secs.dataitems.DRACK.VID_UNKNOWN`        |
        +-------+-------------------------------+----------------------------------------------------------+
        | 5-63  | Reserved, other errors        |                                                          |
        +-------+-------------------------------+----------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS02F34 <secsgem.secs.functions.SecsS02F34>`
    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACK = 0
    INSUFFICIENT_SPACE = 1
    INVALID_FORMAT = 2
    RPTID_REDEFINED = 3
    VID_UNKNOWN = 4


class DSID(DataItemBase):
    """
    Data set ID.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS06F03 <secsgem.secs.functions.SecsS06F03>`
        - :class:`SecsS06F08 <secsgem.secs.functions.SecsS06F08>`
        - :class:`SecsS06F09 <secsgem.secs.functions.SecsS06F09>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString]


class DUTMS(DataItemBase):
    """
    Die units of measure.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`

    """

    __type__ = SecsVarString


class DVNAME(DataItemBase):
    """
    Data value name.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS06F03 <secsgem.secs.functions.SecsS06F03>`
        - :class:`SecsS06F08 <secsgem.secs.functions.SecsS06F08>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString]


class DVVAL(DataItemBase):
    """
    Data value.

    :Types:
       - :class:`SecsVarArray <secsgem.secs.variables.SecsVarArray>`
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarBoolean <secsgem.secs.variables.SecsVarBoolean>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarF8 <secsgem.secs.variables.SecsVarF8>`
       - :class:`SecsVarF4 <secsgem.secs.variables.SecsVarF4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS06F03 <secsgem.secs.functions.SecsS06F03>`
        - :class:`SecsS06F08 <secsgem.secs.functions.SecsS06F08>`
        - :class:`SecsS06F09 <secsgem.secs.functions.SecsS06F09>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarArray, SecsVarBoolean, SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2,
                        SecsVarI4, SecsVarI8, SecsVarF4, SecsVarF8, SecsVarString, SecsVarBinary]


class EAC(DataItemBase):
    """
    Equipment acknowledge code.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+---------------------------------+-------------------------------------------------------+
        | Value | Description                     | Constant                                              |
        +=======+=================================+=======================================================+
        | 0     | Acknowledge                     | :const:`secsgem.secs.dataitems.EAC.ACK`               |
        +-------+---------------------------------+-------------------------------------------------------+
        | 1     | Denied, not all constants exist | :const:`secsgem.secs.dataitems.EAC.INVALID_CONSTANT`  |
        +-------+---------------------------------+-------------------------------------------------------+
        | 2     | Denied, busy                    | :const:`secsgem.secs.dataitems.EAC.BUSY`              |
        +-------+---------------------------------+-------------------------------------------------------+
        | 3     | Denied, constant out of range   | :const:`secsgem.secs.dataitems.EAC.OUT_OF_RANGE`      |
        +-------+---------------------------------+-------------------------------------------------------+
        | 4-63  | Reserved, equipment specific    |                                                       |
        +-------+---------------------------------+-------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS02F16 <secsgem.secs.functions.SecsS02F16>`
    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACK = 0
    INVALID_CONSTANT = 1
    BUSY = 2
    OUT_OF_RANGE = 3


class ECDEF(DataItemBase):
    """
    Equipment constant default value.

    :Types:
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarBoolean <secsgem.secs.variables.SecsVarBoolean>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarF8 <secsgem.secs.variables.SecsVarF8>`
       - :class:`SecsVarF4 <secsgem.secs.variables.SecsVarF4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F30 <secsgem.secs.functions.SecsS02F30>`
    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarBoolean, SecsVarI8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarF8, SecsVarF4, SecsVarU8,
                        SecsVarU1, SecsVarU2, SecsVarU4, SecsVarString, SecsVarBinary]


class ECID(DataItemBase):
    """
    Equipment constant ID.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F13 <secsgem.secs.functions.SecsS02F13>`
        - :class:`SecsS02F15 <secsgem.secs.functions.SecsS02F15>`
        - :class:`SecsS02F29 <secsgem.secs.functions.SecsS02F29>`
        - :class:`SecsS02F30 <secsgem.secs.functions.SecsS02F30>`
    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString]


class ECMAX(DataItemBase):
    """
    Equipment constant maximum value.

    :Types:
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarBoolean <secsgem.secs.variables.SecsVarBoolean>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarF8 <secsgem.secs.variables.SecsVarF8>`
       - :class:`SecsVarF4 <secsgem.secs.variables.SecsVarF4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F30 <secsgem.secs.functions.SecsS02F30>`
    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarBoolean, SecsVarI8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarF8, SecsVarF4, SecsVarU8,
                        SecsVarU1, SecsVarU2, SecsVarU4, SecsVarString, SecsVarBinary]


class ECMIN(DataItemBase):
    """
    Equipment constant minimum value.

    :Types:
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarBoolean <secsgem.secs.variables.SecsVarBoolean>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarF8 <secsgem.secs.variables.SecsVarF8>`
       - :class:`SecsVarF4 <secsgem.secs.variables.SecsVarF4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F30 <secsgem.secs.functions.SecsS02F30>`
    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarBoolean, SecsVarI8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarF8, SecsVarF4, SecsVarU8,
                        SecsVarU1, SecsVarU2, SecsVarU4, SecsVarString, SecsVarBinary]


class ECNAME(DataItemBase):
    """
    Equipment constant name.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS02F30 <secsgem.secs.functions.SecsS02F30>`
    """

    __type__ = SecsVarString


class ECV(DataItemBase):
    """
    Equipment constant value.

    :Types:
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarBoolean <secsgem.secs.variables.SecsVarBoolean>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarF8 <secsgem.secs.variables.SecsVarF8>`
       - :class:`SecsVarF4 <secsgem.secs.variables.SecsVarF4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F14 <secsgem.secs.functions.SecsS02F14>`
        - :class:`SecsS02F15 <secsgem.secs.functions.SecsS02F15>`
    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarArray, SecsVarBoolean, SecsVarI8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarF8, SecsVarF4,
                        SecsVarU8, SecsVarU1, SecsVarU2, SecsVarU4, SecsVarString, SecsVarBinary]


class EDID(DataItemBase):
    """
    Expected data identification.

    :Types:
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS09F13 <secsgem.secs.functions.SecsS09F13>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString, SecsVarBinary]


class ERACK(DataItemBase):
    """
    Enable/disable event report acknowledge.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+----------------------------+----------------------------------------------------+
        | Value | Description                | Constant                                           |
        +=======+============================+====================================================+
        | 0     | Accepted                   | :const:`secsgem.secs.dataitems.ERACK.ACCEPTED`     |
        +-------+----------------------------+----------------------------------------------------+
        | 1     | Denied, CEID doesn't exist | :const:`secsgem.secs.dataitems.ERACK.CEID_UNKNOWN` |
        +-------+----------------------------+----------------------------------------------------+
        | 2-63  | Reserved                   |                                                    |
        +-------+----------------------------+----------------------------------------------------+

    **Used In Function**
        - :class:`SecsS02F38 <secsgem.secs.functions.SecsS02F38>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACCEPTED = 0
    CEID_UNKNOWN = 1


class ERRCODE(DataItemBase):
    """
    Reference point.

    :Types:
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`

    **Used In Function**
        - :class:`SecsS01F03 <secsgem.secs.functions.SecsS01F03>`
        - :class:`SecsS01F20 <secsgem.secs.functions.SecsS01F20>`
        - :class:`SecsS03F16 <secsgem.secs.functions.SecsS03F16>`
        - :class:`SecsS03F30 <secsgem.secs.functions.SecsS03F30>`
        - :class:`SecsS03F32 <secsgem.secs.functions.SecsS03F32>`
        - :class:`SecsS04F20 <secsgem.secs.functions.SecsS04F20>`
        - :class:`SecsS04F22 <secsgem.secs.functions.SecsS04F22>`
        - :class:`SecsS04F23 <secsgem.secs.functions.SecsS04F23>`
        - :class:`SecsS04F33 <secsgem.secs.functions.SecsS04F33>`
        - :class:`SecsS04F35 <secsgem.secs.functions.SecsS04F35>`
        - :class:`SecsS05F14 <secsgem.secs.functions.SecsS05F14>`
        - :class:`SecsS05F15 <secsgem.secs.functions.SecsS05F15>`
        - :class:`SecsS05F18 <secsgem.secs.functions.SecsS05F18>`
        - :class:`SecsS13F14 <secsgem.secs.functions.SecsS13F14>`
        - :class:`SecsS13F16 <secsgem.secs.functions.SecsS13F16>`
        - :class:`SecsS14F02 <secsgem.secs.functions.SecsS14F02>`
        - :class:`SecsS14F04 <secsgem.secs.functions.SecsS14F04>`
        - :class:`SecsS14F06 <secsgem.secs.functions.SecsS14F06>`
        - :class:`SecsS14F08 <secsgem.secs.functions.SecsS14F08>`
        - :class:`SecsS14F10 <secsgem.secs.functions.SecsS14F10>`
        - :class:`SecsS14F12 <secsgem.secs.functions.SecsS14F12>`
        - :class:`SecsS14F14 <secsgem.secs.functions.SecsS14F14>`
        - :class:`SecsS14F16 <secsgem.secs.functions.SecsS14F16>`
        - :class:`SecsS14F18 <secsgem.secs.functions.SecsS14F18>`
        - :class:`SecsS14F26 <secsgem.secs.functions.SecsS14F26>`
        - :class:`SecsS14F28 <secsgem.secs.functions.SecsS14F28>`
        - :class:`SecsS15F18 <secsgem.secs.functions.SecsS15F18>`
        - :class:`SecsS15F20 <secsgem.secs.functions.SecsS15F20>`
        - :class:`SecsS15F22 <secsgem.secs.functions.SecsS15F22>`
        - :class:`SecsS15F24 <secsgem.secs.functions.SecsS15F24>`
        - :class:`SecsS15F26 <secsgem.secs.functions.SecsS15F26>`
        - :class:`SecsS15F28 <secsgem.secs.functions.SecsS15F28>`
        - :class:`SecsS15F30 <secsgem.secs.functions.SecsS15F30>`
        - :class:`SecsS15F32 <secsgem.secs.functions.SecsS15F32>`
        - :class:`SecsS15F34 <secsgem.secs.functions.SecsS15F34>`
        - :class:`SecsS15F36 <secsgem.secs.functions.SecsS15F36>`
        - :class:`SecsS15F38 <secsgem.secs.functions.SecsS15F38>`
        - :class:`SecsS15F40 <secsgem.secs.functions.SecsS15F40>`
        - :class:`SecsS15F42 <secsgem.secs.functions.SecsS15F42>`
        - :class:`SecsS15F44 <secsgem.secs.functions.SecsS15F44>`
        - :class:`SecsS15F48 <secsgem.secs.functions.SecsS15F48>`
        - :class:`SecsS15F53 <secsgem.secs.functions.SecsS15F53>`
        - :class:`SecsS16F12 <secsgem.secs.functions.SecsS16F12>`
        - :class:`SecsS16F14 <secsgem.secs.functions.SecsS16F14>`
        - :class:`SecsS16F16 <secsgem.secs.functions.SecsS16F16>`
        - :class:`SecsS16F18 <secsgem.secs.functions.SecsS16F18>`
        - :class:`SecsS16F24 <secsgem.secs.functions.SecsS16F24>`
        - :class:`SecsS16F26 <secsgem.secs.functions.SecsS16F26>`
        - :class:`SecsS16F28 <secsgem.secs.functions.SecsS16F28>`
        - :class:`SecsS17F02 <secsgem.secs.functions.SecsS17F02>`
        - :class:`SecsS17F04 <secsgem.secs.functions.SecsS17F04>`
        - :class:`SecsS17F06 <secsgem.secs.functions.SecsS17F06>`
        - :class:`SecsS17F08 <secsgem.secs.functions.SecsS17F08>`
        - :class:`SecsS17F10 <secsgem.secs.functions.SecsS17F10>`
        - :class:`SecsS17F12 <secsgem.secs.functions.SecsS17F12>`
        - :class:`SecsS17F14 <secsgem.secs.functions.SecsS17F14>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8]


class ERRTEXT(DataItemBase):
    """
    Error description for error code.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS01F20 <secsgem.secs.functions.SecsS01F20>`
        - :class:`SecsS03F16 <secsgem.secs.functions.SecsS03F16>`
        - :class:`SecsS03F18 <secsgem.secs.functions.SecsS03F18>`
        - :class:`SecsS03F20 <secsgem.secs.functions.SecsS03F20>`
        - :class:`SecsS03F22 <secsgem.secs.functions.SecsS03F22>`
        - :class:`SecsS03F24 <secsgem.secs.functions.SecsS03F24>`
        - :class:`SecsS03F26 <secsgem.secs.functions.SecsS03F26>`
        - :class:`SecsS03F30 <secsgem.secs.functions.SecsS03F30>`
        - :class:`SecsS03F32 <secsgem.secs.functions.SecsS03F32>`
        - :class:`SecsS04F20 <secsgem.secs.functions.SecsS04F20>`
        - :class:`SecsS04F22 <secsgem.secs.functions.SecsS04F22>`
        - :class:`SecsS04F23 <secsgem.secs.functions.SecsS04F23>`
        - :class:`SecsS04F33 <secsgem.secs.functions.SecsS04F33>`
        - :class:`SecsS04F35 <secsgem.secs.functions.SecsS04F35>`
        - :class:`SecsS05F14 <secsgem.secs.functions.SecsS05F14>`
        - :class:`SecsS05F15 <secsgem.secs.functions.SecsS05F15>`
        - :class:`SecsS05F18 <secsgem.secs.functions.SecsS05F18>`
        - :class:`SecsS13F14 <secsgem.secs.functions.SecsS13F14>`
        - :class:`SecsS13F16 <secsgem.secs.functions.SecsS13F16>`
        - :class:`SecsS14F02 <secsgem.secs.functions.SecsS14F02>`
        - :class:`SecsS14F04 <secsgem.secs.functions.SecsS14F04>`
        - :class:`SecsS14F06 <secsgem.secs.functions.SecsS14F06>`
        - :class:`SecsS14F08 <secsgem.secs.functions.SecsS14F08>`
        - :class:`SecsS14F10 <secsgem.secs.functions.SecsS14F10>`
        - :class:`SecsS14F12 <secsgem.secs.functions.SecsS14F12>`
        - :class:`SecsS14F14 <secsgem.secs.functions.SecsS14F14>`
        - :class:`SecsS14F16 <secsgem.secs.functions.SecsS14F16>`
        - :class:`SecsS14F18 <secsgem.secs.functions.SecsS14F18>`
        - :class:`SecsS14F26 <secsgem.secs.functions.SecsS14F26>`
        - :class:`SecsS14F28 <secsgem.secs.functions.SecsS14F28>`
        - :class:`SecsS15F28 <secsgem.secs.functions.SecsS15F28>`
        - :class:`SecsS15F30 <secsgem.secs.functions.SecsS15F30>`
        - :class:`SecsS15F32 <secsgem.secs.functions.SecsS15F32>`
        - :class:`SecsS15F34 <secsgem.secs.functions.SecsS15F34>`
        - :class:`SecsS15F36 <secsgem.secs.functions.SecsS15F36>`
        - :class:`SecsS15F38 <secsgem.secs.functions.SecsS15F38>`
        - :class:`SecsS15F40 <secsgem.secs.functions.SecsS15F40>`
        - :class:`SecsS15F42 <secsgem.secs.functions.SecsS15F42>`
        - :class:`SecsS15F44 <secsgem.secs.functions.SecsS15F44>`
        - :class:`SecsS15F48 <secsgem.secs.functions.SecsS15F48>`
        - :class:`SecsS15F53 <secsgem.secs.functions.SecsS15F53>`
        - :class:`SecsS16F12 <secsgem.secs.functions.SecsS16F12>`
        - :class:`SecsS16F14 <secsgem.secs.functions.SecsS16F14>`
        - :class:`SecsS16F16 <secsgem.secs.functions.SecsS16F16>`
        - :class:`SecsS16F18 <secsgem.secs.functions.SecsS16F18>`
        - :class:`SecsS16F24 <secsgem.secs.functions.SecsS16F24>`
        - :class:`SecsS16F26 <secsgem.secs.functions.SecsS16F26>`
        - :class:`SecsS16F28 <secsgem.secs.functions.SecsS16F28>`
        - :class:`SecsS17F04 <secsgem.secs.functions.SecsS17F04>`
        - :class:`SecsS17F08 <secsgem.secs.functions.SecsS17F08>`
        - :class:`SecsS17F14 <secsgem.secs.functions.SecsS17F14>`

    """

    __type__ = SecsVarString
    __count__ = 120


class EXID(DataItemBase):
    """
    Exception identifier.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS05F09 <secsgem.secs.functions.SecsS05F09>`
        - :class:`SecsS05F11 <secsgem.secs.functions.SecsS05F11>`
        - :class:`SecsS05F13 <secsgem.secs.functions.SecsS05F13>`
        - :class:`SecsS05F14 <secsgem.secs.functions.SecsS05F14>`
        - :class:`SecsS05F15 <secsgem.secs.functions.SecsS05F15>`
        - :class:`SecsS05F17 <secsgem.secs.functions.SecsS05F17>`
        - :class:`SecsS05F18 <secsgem.secs.functions.SecsS05F18>`
    """

    __type__ = SecsVarString
    __count__ = 20


class EXMESSAGE(DataItemBase):
    """
    Exception message.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS05F09 <secsgem.secs.functions.SecsS05F09>`
        - :class:`SecsS05F11 <secsgem.secs.functions.SecsS05F11>`
    """

    __type__ = SecsVarString


class EXRECVRA(DataItemBase):
    """
    Exception recovery action.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS05F09 <secsgem.secs.functions.SecsS05F09>`
        - :class:`SecsS05F13 <secsgem.secs.functions.SecsS05F13>`
    """

    __type__ = SecsVarString
    __count__ = 40


class EXTYPE(DataItemBase):
    """
    Exception type.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS05F09 <secsgem.secs.functions.SecsS05F09>`
        - :class:`SecsS05F11 <secsgem.secs.functions.SecsS05F11>`
        - :class:`SecsS14F01 <secsgem.secs.functions.SecsS14F01>`
        - :class:`SecsS14F02 <secsgem.secs.functions.SecsS14F02>`
        - :class:`SecsS14F08 <secsgem.secs.functions.SecsS14F08>`
    """

    __type__ = SecsVarString


class FFROT(DataItemBase):
    """
    Film frame rotation.

    In degrees from the bottom CW. (Bottom equals zero degrees.) Zero length indicates not used.

       :Types: :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F03 <secsgem.secs.functions.SecsS12F03>`

    """

    __type__ = SecsVarU2


class FNLOC(DataItemBase):
    """
    Flat/notch location.

    In degrees from the bottom CW. (Bottom equals zero degrees.) Zero length indicates not used.

       :Types: :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F03 <secsgem.secs.functions.SecsS12F03>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`

    """

    __type__ = SecsVarU2


class GRANT6(DataItemBase):
    """
    Permission to send.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+----------------+-------------------------------------------------------+
        | Value | Description    | Constant                                              |
        +=======+================+=======================================================+
        | 0     | Granted        | :const:`secsgem.secs.dataitems.GRANT6.GRANTED`        |
        +-------+----------------+-------------------------------------------------------+
        | 1     | Busy           | :const:`secsgem.secs.dataitems.GRANT6.BUSY`           |
        +-------+----------------+-------------------------------------------------------+
        | 2     | Not interested | :const:`secsgem.secs.dataitems.GRANT6.NOT_INTERESTED` |
        +-------+----------------+-------------------------------------------------------+
        | 3-63  | Other error    |                                                       |
        +-------+----------------+-------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS06F06 <secsgem.secs.functions.SecsS06F06>`
    """

    __type__ = SecsVarBinary
    __count__ = 1

    GRANTED = 0
    BUSY = 1
    NOT_INTERESTED = 2


class GRNT1(DataItemBase):
    """
    Grant code.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+-----------------------+----------------------------------------------------------+
        | Value | Description           | Constant                                                 |
        +=======+=======================+==========================================================+
        | 0     | Acknowledge           | :const:`secsgem.secs.dataitems.GRNT1.ACK`                |
        +-------+-----------------------+----------------------------------------------------------+
        | 1     | Busy, try again       | :const:`secsgem.secs.dataitems.GRNT1.BUSY`               |
        +-------+-----------------------+----------------------------------------------------------+
        | 2     | No space              | :const:`secsgem.secs.dataitems.GRNT1.NO_SPACE`           |
        +-------+-----------------------+----------------------------------------------------------+
        | 3     | Map too large         | :const:`secsgem.secs.dataitems.GRNT1.MAP_TOO_LARGE`      |
        +-------+-----------------------+----------------------------------------------------------+
        | 4     | Duplicate ID          | :const:`secsgem.secs.dataitems.GRNT1.DUPLICATE_ID`       |
        +-------+-----------------------+----------------------------------------------------------+
        | 5     | Material ID not found | :const:`secsgem.secs.dataitems.GRNT1.MATERIALID_UNKNOWN` |
        +-------+-----------------------+----------------------------------------------------------+
        | 6     | Unknown map format    | :const:`secsgem.secs.dataitems.GRNT1.UNKNOWN_MAP_FORMAT` |
        +-------+-----------------------+----------------------------------------------------------+
        | 7-63  | Reserved, error       |                                                          |
        +-------+-----------------------+----------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS12F06 <secsgem.secs.functions.SecsS12F06>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACK = 0
    BUSY = 1
    NO_SPACE = 2
    MAP_TOO_LARGE = 3
    DUPLICATE_ID = 4
    MATERIALID_UNKNOWN = 5
    UNKNOWN_MAP_FORMAT = 6


class HCACK(DataItemBase):
    """
    Host command parameter acknowledge code.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+--------------------------------+------------------------------------------------------------+
        | Value | Description                    | Constant                                                   |
        +=======+================================+============================================================+
        | 0     | Acknowledge                    | :const:`secsgem.secs.dataitems.HCACK.ACK`                  |
        +-------+--------------------------------+------------------------------------------------------------+
        | 1     | Denied, invalid command        | :const:`secsgem.secs.dataitems.HCACK.INVALID_COMMAND`      |
        +-------+--------------------------------+------------------------------------------------------------+
        | 2     | Denied, cannot perform now     | :const:`secsgem.secs.dataitems.HCACK.CANT_PERFORM_NOW`     |
        +-------+--------------------------------+------------------------------------------------------------+
        | 3     | Denied, parameter invalid      | :const:`secsgem.secs.dataitems.HCACK.PARAMETER_INVALID`    |
        +-------+--------------------------------+------------------------------------------------------------+
        | 4     | Acknowledge, will finish later | :const:`secsgem.secs.dataitems.HCACK.ACK_FINISH_LATER`     |
        +-------+--------------------------------+------------------------------------------------------------+
        | 5     | Rejected, already in condition | :const:`secsgem.secs.dataitems.HCACK.ALREADY_IN_CONDITION` |
        +-------+--------------------------------+------------------------------------------------------------+
        | 6     | No such object                 | :const:`secsgem.secs.dataitems.HCACK.NO_OBJECT`            |
        +-------+--------------------------------+------------------------------------------------------------+
        | 7-63  | Reserved                       |                                                            |
        +-------+--------------------------------+------------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS02F42 <secsgem.secs.functions.SecsS02F42>`
        - :class:`SecsS02F50 <secsgem.secs.functions.SecsS02F50>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACK = 0
    INVALID_COMMAND = 1
    CANT_PERFORM_NOW = 2
    PARAMETER_INVALID = 3
    ACK_FINISH_LATER = 4
    ALREADY_IN_CONDITION = 5
    NO_OBJECT = 6


class IDTYP(DataItemBase):
    """
    ID type.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+-------------------+------------------------------------------------------+
        | Value | Description       | Constant                                             |
        +=======+===================+======================================================+
        | 0     | Wafer ID          | :const:`secsgem.secs.dataitems.IDTYP.WAFER`          |
        +-------+-------------------+------------------------------------------------------+
        | 1     | Wafer cassette ID | :const:`secsgem.secs.dataitems.IDTYP.WAFER_CASSETTE` |
        +-------+-------------------+------------------------------------------------------+
        | 2     | Film frame ID     | :const:`secsgem.secs.dataitems.IDTYP.FILM_FRAME`     |
        +-------+-------------------+------------------------------------------------------+
        | 3-63  | Reserved, error   |                                                      |
        +-------+-------------------+------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F03 <secsgem.secs.functions.SecsS12F03>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`
        - :class:`SecsS12F05 <secsgem.secs.functions.SecsS12F06>`
        - :class:`SecsS12F07 <secsgem.secs.functions.SecsS12F07>`
        - :class:`SecsS12F09 <secsgem.secs.functions.SecsS12F09>`
        - :class:`SecsS12F11 <secsgem.secs.functions.SecsS12F11>`
        - :class:`SecsS12F13 <secsgem.secs.functions.SecsS12F13>`
        - :class:`SecsS12F14 <secsgem.secs.functions.SecsS12F14>`
        - :class:`SecsS12F15 <secsgem.secs.functions.SecsS12F15>`
        - :class:`SecsS12F16 <secsgem.secs.functions.SecsS12F16>`
        - :class:`SecsS12F17 <secsgem.secs.functions.SecsS12F17>`
        - :class:`SecsS12F18 <secsgem.secs.functions.SecsS12F18>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    WAFER = 0
    WAFER_CASSETTE = 1
    FILM_FRAME = 2


class LENGTH(DataItemBase):
    """
    Service/process program length.

    :Types:
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F01 <secsgem.secs.functions.SecsS02F01>`
        - :class:`SecsS07F01 <secsgem.secs.functions.SecsS07F01>`
        - :class:`SecsS07F29 <secsgem.secs.functions.SecsS07F29>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8]


class LRACK(DataItemBase):
    """
    Link report acknowledge code.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+-----------------------------+----------------------------------------------------------+
        | Value | Description                 | Constant                                                 |
        +=======+=============================+==========================================================+
        | 0     | Acknowledge                 | :const:`secsgem.secs.dataitems.LRACK.ACK`                |
        +-------+-----------------------------+----------------------------------------------------------+
        | 1     | Denied, insufficient space  | :const:`secsgem.secs.dataitems.LRACK.INSUFFICIENT_SPACE` |
        +-------+-----------------------------+----------------------------------------------------------+
        | 2     | Denied, invalid format      | :const:`secsgem.secs.dataitems.LRACK.INVALID_FORMAT`     |
        +-------+-----------------------------+----------------------------------------------------------+
        | 3     | Denied, CEID already linked | :const:`secsgem.secs.dataitems.LRACK.CEID_LINKED`        |
        +-------+-----------------------------+----------------------------------------------------------+
        | 4     | Denied, CEID doesn't exist  | :const:`secsgem.secs.dataitems.LRACK.CEID_UNKNOWN`       |
        +-------+-----------------------------+----------------------------------------------------------+
        | 5     | Denied, RPTID doesn't exist | :const:`secsgem.secs.dataitems.LRACK.RPTID_UNKNOWN`      |
        +-------+-----------------------------+----------------------------------------------------------+
        | 6-63  | Reserved, other errors      |                                                          |
        +-------+-----------------------------+----------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS02F36 <secsgem.secs.functions.SecsS02F36>`
    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACK = 0
    INSUFFICIENT_SPACE = 1
    INVALID_FORMAT = 2
    CEID_LINKED = 3
    CEID_UNKNOWN = 4
    RPTID_UNKNOWN = 5


class MAPER(DataItemBase):
    """
    Map error.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+---------------+----------------------------------------------------+
        | Value | Description   | Constant                                           |
        +=======+===============+====================================================+
        | 0     | ID not found  | :const:`secsgem.secs.dataitems.MAPER.ID_UNKNOWN`   |
        +-------+---------------+----------------------------------------------------+
        | 1     | Invalid data  | :const:`secsgem.secs.dataitems.MAPER.INVALID_DATA` |
        +-------+---------------+----------------------------------------------------+
        | 2     | Format error  | :const:`secsgem.secs.dataitems.MAPER.FORMAT_ERROR` |
        +-------+---------------+----------------------------------------------------+
        | 3-63  | Invalid error |                                                    |
        +-------+---------------+----------------------------------------------------+

    **Used In Function**
        - :class:`SecsS12F19 <secsgem.secs.functions.SecsS12F19>`
    """

    __type__ = SecsVarBinary
    __count__ = 1

    ID_UNKNOWN = 0
    INVALID_DATA = 1
    FORMAT_ERROR = 2


class MAPFT(DataItemBase):
    """
    Map data format.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+-------------------+--------------------------------------------------+
        | Value | Description       | Constant                                         |
        +=======+===================+==================================================+
        | 0     | Row format        | :const:`secsgem.secs.dataitems.MAPFT.ROW`        |
        +-------+-------------------+--------------------------------------------------+
        | 1     | Array format      | :const:`secsgem.secs.dataitems.MAPFT.ARRAY`      |
        +-------+-------------------+--------------------------------------------------+
        | 2     | Coordinate format | :const:`secsgem.secs.dataitems.MAPFT.COORDINATE` |
        +-------+-------------------+--------------------------------------------------+
        | 3-63  | Error             |                                                  |
        +-------+-------------------+--------------------------------------------------+

    **Used In Function**
        - :class:`SecsS12F03 <secsgem.secs.functions.SecsS12F03>`
        - :class:`SecsS12F05 <secsgem.secs.functions.SecsS12F05>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    ROW = 0
    ARRAY = 1
    COORDINATE = 2


class MDACK(DataItemBase):
    """
    Map data acknowledge.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+-------------------+----------------------------------------------------+
        | Value | Description       | Constant                                           |
        +=======+===================+====================================================+
        | 0     | Map received      | :const:`secsgem.secs.dataitems.MDACK.ACK`          |
        +-------+-------------------+----------------------------------------------------+
        | 1     | Format error      | :const:`secsgem.secs.dataitems.MDACK.FORMAT_ERROR` |
        +-------+-------------------+----------------------------------------------------+
        | 2     | No ID match       | :const:`secsgem.secs.dataitems.MDACK.UNKNOWN_ID`   |
        +-------+-------------------+----------------------------------------------------+
        | 3     | Abort/discard map | :const:`secsgem.secs.dataitems.MDACK.ABORT_MAP`    |
        +-------+-------------------+----------------------------------------------------+
        | 4-63  | Reserved, error   |                                                    |
        +-------+-------------------+----------------------------------------------------+

    **Used In Function**
        - :class:`SecsS12F08 <secsgem.secs.functions.SecsS12F08>`
        - :class:`SecsS12F10 <secsgem.secs.functions.SecsS12F10>`
        - :class:`SecsS12F12 <secsgem.secs.functions.SecsS12F12>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACK = 0
    FORMAT_ERROR = 1
    UNKNOWN_ID = 2
    ABORT_MAP = 3


class MDLN(DataItemBase):
    """
    Equipment model type.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS01F02 <secsgem.secs.functions.SecsS01F02>`
        - :class:`SecsS01F13 <secsgem.secs.functions.SecsS01F13>`
        - :class:`SecsS01F14 <secsgem.secs.functions.SecsS01F14>`
        - :class:`SecsS07F22 <secsgem.secs.functions.SecsS07F22>`
        - :class:`SecsS07F23 <secsgem.secs.functions.SecsS07F23>`
        - :class:`SecsS07F26 <secsgem.secs.functions.SecsS07F26>`
        - :class:`SecsS07F31 <secsgem.secs.functions.SecsS07F31>`
        - :class:`SecsS07F39 <secsgem.secs.functions.SecsS07F39>`
        - :class:`SecsS07F43 <secsgem.secs.functions.SecsS07F43>`

    """

    __type__ = SecsVarString
    __count__ = 20


class MEXP(DataItemBase):
    """
    Message expected.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS09F13 <secsgem.secs.functions.SecsS09F13>`
    """

    __type__ = SecsVarString
    __count__ = 6


class MHEAD(DataItemBase):
    """
    SECS message header.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 10

    **Used In Function**
        - :class:`SecsS09F01 <secsgem.secs.functions.SecsS09F01>`
        - :class:`SecsS09F03 <secsgem.secs.functions.SecsS09F03>`
        - :class:`SecsS09F05 <secsgem.secs.functions.SecsS09F05>`
        - :class:`SecsS09F07 <secsgem.secs.functions.SecsS09F07>`
        - :class:`SecsS09F11 <secsgem.secs.functions.SecsS09F11>`

    """

    __type__ = SecsVarBinary
    __count__ = 10


class MID(DataItemBase):
    """
    Material ID.

    :Types:
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS02F27 <secsgem.secs.functions.SecsS02F27>`
        - :class:`SecsS03F02 <secsgem.secs.functions.SecsS03F02>`
        - :class:`SecsS03F04 <secsgem.secs.functions.SecsS03F04>`
        - :class:`SecsS03F07 <secsgem.secs.functions.SecsS03F07>`
        - :class:`SecsS03F09 <secsgem.secs.functions.SecsS03F09>`
        - :class:`SecsS03F12 <secsgem.secs.functions.SecsS03F12>`
        - :class:`SecsS03F13 <secsgem.secs.functions.SecsS03F13>`
        - :class:`SecsS04F01 <secsgem.secs.functions.SecsS04F01>`
        - :class:`SecsS04F03 <secsgem.secs.functions.SecsS04F03>`
        - :class:`SecsS04F05 <secsgem.secs.functions.SecsS04F05>`
        - :class:`SecsS04F07 <secsgem.secs.functions.SecsS04F07>`
        - :class:`SecsS04F09 <secsgem.secs.functions.SecsS04F09>`
        - :class:`SecsS04F11 <secsgem.secs.functions.SecsS04F11>`
        - :class:`SecsS04F13 <secsgem.secs.functions.SecsS04F13>`
        - :class:`SecsS04F15 <secsgem.secs.functions.SecsS04F15>`
        - :class:`SecsS04F17 <secsgem.secs.functions.SecsS04F17>`
        - :class:`SecsS07F07 <secsgem.secs.functions.SecsS07F07>`
        - :class:`SecsS07F08 <secsgem.secs.functions.SecsS07F08>`
        - :class:`SecsS07F10 <secsgem.secs.functions.SecsS07F10>`
        - :class:`SecsS07F11 <secsgem.secs.functions.SecsS07F11>`
        - :class:`SecsS07F13 <secsgem.secs.functions.SecsS07F13>`
        - :class:`SecsS07F35 <secsgem.secs.functions.SecsS07F35>`
        - :class:`SecsS07F36 <secsgem.secs.functions.SecsS07F36>`
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F03 <secsgem.secs.functions.SecsS12F03>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`
        - :class:`SecsS12F05 <secsgem.secs.functions.SecsS12F05>`
        - :class:`SecsS12F07 <secsgem.secs.functions.SecsS12F07>`
        - :class:`SecsS12F09 <secsgem.secs.functions.SecsS12F09>`
        - :class:`SecsS12F11 <secsgem.secs.functions.SecsS12F11>`
        - :class:`SecsS12F13 <secsgem.secs.functions.SecsS12F13>`
        - :class:`SecsS12F14 <secsgem.secs.functions.SecsS12F14>`
        - :class:`SecsS12F15 <secsgem.secs.functions.SecsS12F15>`
        - :class:`SecsS12F16 <secsgem.secs.functions.SecsS12F16>`
        - :class:`SecsS12F17 <secsgem.secs.functions.SecsS12F17>`
        - :class:`SecsS12F18 <secsgem.secs.functions.SecsS12F18>`
        - :class:`SecsS16F11 <secsgem.secs.functions.SecsS16F11>`
        - :class:`SecsS16F13 <secsgem.secs.functions.SecsS16F13>`
        - :class:`SecsS16F15 <secsgem.secs.functions.SecsS16F15>`
        - :class:`SecsS18F10 <secsgem.secs.functions.SecsS18F10>`
        - :class:`SecsS18F11 <secsgem.secs.functions.SecsS18F11>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarString, SecsVarBinary]
    __count__ = 80


class MLCL(DataItemBase):
    """
    Message length.

    :Types:
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`
        - :class:`SecsS12F05 <secsgem.secs.functions.SecsS12F05>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8]


class NULBC(DataItemBase):
    """
    Column count in dies.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F03 <secsgem.secs.functions.SecsS12F03>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarString]


class OBJACK(DataItemBase):
    """
    Object acknowledgement code.

       :Types: :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       :Length: 1

    **Values**
        +-------+-------------+---------------------------------------------------+
        | Value | Description | Constant                                          |
        +=======+=============+===================================================+
        | 0     | Successful  | :const:`secsgem.secs.dataitems.OBJACK.SUCCESSFUL` |
        +-------+-------------+---------------------------------------------------+
        | 1     | Error       | :const:`secsgem.secs.dataitems.OBJACK.ERROR`      |
        +-------+-------------+---------------------------------------------------+
        | 2-63  | Reserved    |                                                   |
        +-------+-------------+---------------------------------------------------+

    **Used In Function**
        - :class:`SecsS14F02 <secsgem.secs.functions.SecsS14F02>`
        - :class:`SecsS14F04 <secsgem.secs.functions.SecsS14F04>`
        - :class:`SecsS14F06 <secsgem.secs.functions.SecsS14F06>`
        - :class:`SecsS14F08 <secsgem.secs.functions.SecsS14F08>`
        - :class:`SecsS14F10 <secsgem.secs.functions.SecsS14F10>`
        - :class:`SecsS14F12 <secsgem.secs.functions.SecsS14F12>`
        - :class:`SecsS14F14 <secsgem.secs.functions.SecsS14F14>`
        - :class:`SecsS14F16 <secsgem.secs.functions.SecsS14F16>`
        - :class:`SecsS14F18 <secsgem.secs.functions.SecsS14F18>`
        - :class:`SecsS14F26 <secsgem.secs.functions.SecsS14F26>`
        - :class:`SecsS14F28 <secsgem.secs.functions.SecsS14F28>`

    """

    __type__ = SecsVarU1
    __count__ = 1

    SUCCESSFUL = 0
    ERROR = 1


class OBJID(DataItemBase):
    """
    Object identifier.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS01F19 <secsgem.secs.functions.SecsS01F19>`
        - :class:`SecsS14F01 <secsgem.secs.functions.SecsS14F01>`
        - :class:`SecsS14F02 <secsgem.secs.functions.SecsS14F02>`
        - :class:`SecsS14F03 <secsgem.secs.functions.SecsS14F03>`
        - :class:`SecsS14F04 <secsgem.secs.functions.SecsS14F04>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarString]


class OBJSPEC(DataItemBase):
    """
    Specific object instance.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS02F49 <secsgem.secs.functions.SecsS02F49>`
        - :class:`SecsS13F11 <secsgem.secs.functions.SecsS13F11>`
        - :class:`SecsS13F13 <secsgem.secs.functions.SecsS13F13>`
        - :class:`SecsS13F15 <secsgem.secs.functions.SecsS13F15>`
        - :class:`SecsS14F01 <secsgem.secs.functions.SecsS14F01>`
        - :class:`SecsS14F03 <secsgem.secs.functions.SecsS14F03>`
        - :class:`SecsS14F05 <secsgem.secs.functions.SecsS14F05>`
        - :class:`SecsS14F07 <secsgem.secs.functions.SecsS14F07>`
        - :class:`SecsS14F09 <secsgem.secs.functions.SecsS14F09>`
        - :class:`SecsS14F10 <secsgem.secs.functions.SecsS14F10>`
        - :class:`SecsS14F11 <secsgem.secs.functions.SecsS14F11>`
        - :class:`SecsS14F13 <secsgem.secs.functions.SecsS14F13>`
        - :class:`SecsS14F15 <secsgem.secs.functions.SecsS14F15>`
        - :class:`SecsS14F16 <secsgem.secs.functions.SecsS14F16>`
        - :class:`SecsS14F17 <secsgem.secs.functions.SecsS14F17>`
        - :class:`SecsS14F19 <secsgem.secs.functions.SecsS14F19>`
        - :class:`SecsS14F25 <secsgem.secs.functions.SecsS14F25>`
        - :class:`SecsS14F27 <secsgem.secs.functions.SecsS14F27>`
        - :class:`SecsS15F43 <secsgem.secs.functions.SecsS15F43>`
        - :class:`SecsS15F47 <secsgem.secs.functions.SecsS15F47>`

    """

    __type__ = SecsVarString


class OBJTYPE(DataItemBase):
    """
    Class of object identifier.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS01F19 <secsgem.secs.functions.SecsS01F19>`
        - :class:`SecsS14F01 <secsgem.secs.functions.SecsS14F01>`
        - :class:`SecsS14F03 <secsgem.secs.functions.SecsS14F03>`
        - :class:`SecsS14F06 <secsgem.secs.functions.SecsS14F06>`
        - :class:`SecsS14F07 <secsgem.secs.functions.SecsS14F07>`
        - :class:`SecsS14F08 <secsgem.secs.functions.SecsS14F08>`
        - :class:`SecsS14F25 <secsgem.secs.functions.SecsS14F25>`
        - :class:`SecsS14F26 <secsgem.secs.functions.SecsS14F26>`
        - :class:`SecsS14F27 <secsgem.secs.functions.SecsS14F27>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarString]


class OFLACK(DataItemBase):
    """
    Acknowledge code for OFFLINE request.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+---------------------+--------------------------------------------+
        | Value | Description         | Constant                                   |
        +=======+=====================+============================================+
        | 0     | OFFLINE Acknowledge | :const:`secsgem.secs.dataitems.OFLACK.ACK` |
        +-------+---------------------+--------------------------------------------+
        | 1-63  | Reserved            |                                            |
        +-------+---------------------+--------------------------------------------+

    **Used In Function**
        - :class:`SecsS01F16 <secsgem.secs.functions.SecsS01F16>`
    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACK = 0


class ONLACK(DataItemBase):
    """
    Acknowledge code for ONLINE request.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+--------------------+----------------------------------------------------+
        | Value | Description        | Constant                                           |
        +=======+====================+====================================================+
        | 0     | ONLINE Accepted    | :const:`secsgem.secs.dataitems.ONLACK.ACCEPTED`    |
        +-------+--------------------+----------------------------------------------------+
        | 1     | ONLINE Not allowed | :const:`secsgem.secs.dataitems.ONLACK.NOT_ALLOWED` |
        +-------+--------------------+----------------------------------------------------+
        | 2     | Already ONLINE     | :const:`secsgem.secs.dataitems.ONLACK.ALREADY_ON`  |
        +-------+--------------------+----------------------------------------------------+
        | 3-63  | Reserved           |                                                    |
        +-------+--------------------+----------------------------------------------------+

    **Used In Function**
        - :class:`SecsS01F18 <secsgem.secs.functions.SecsS01F18>`
    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACCEPTED = 0
    NOT_ALLOWED = 1
    ALREADY_ON = 2


class ORLOC(DataItemBase):
    """
    Origin location.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+---------------------+---------------------------------------------------+
        | Value | Description         | Constant                                          |
        +=======+=====================+===================================================+
        | 0     | Center die of wafer | :const:`secsgem.secs.dataitems.ORLOC.CENTER_DIE`  |
        +-------+---------------------+---------------------------------------------------+
        | 1     | Upper right         | :const:`secsgem.secs.dataitems.ORLOC.UPPER_RIGHT` |
        +-------+---------------------+---------------------------------------------------+
        | 2     | Upper left          | :const:`secsgem.secs.dataitems.ORLOC.UPPER_LEFT`  |
        +-------+---------------------+---------------------------------------------------+
        | 3     | Lower left          | :const:`secsgem.secs.dataitems.ORLOC.LOWER_LEFT`  |
        +-------+---------------------+---------------------------------------------------+
        | 4     | Lower right         | :const:`secsgem.secs.dataitems.ORLOC.LOWER_RIGHT` |
        +-------+---------------------+---------------------------------------------------+
        | 5-63  | Reserved, error     |                                                   |
        +-------+---------------------+---------------------------------------------------+

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F03 <secsgem.secs.functions.SecsS12F03>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`

    """

    __type__ = SecsVarBinary

    CENTER_DIE = 0
    UPPER_RIGHT = 1
    UPPER_LEFT = 2
    LOWER_LEFT = 3
    LOWER_RIGHT = 3


class PPBODY(DataItemBase):
    """
    Status variable ID.

    :Types:
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS07F03 <secsgem.secs.functions.SecsS07F03>`
        - :class:`SecsS07F06 <secsgem.secs.functions.SecsS07F06>`
        - :class:`SecsS07F36 <secsgem.secs.functions.SecsS07F36>`
        - :class:`SecsS07F37 <secsgem.secs.functions.SecsS07F37>`
        - :class:`SecsS07F41 <secsgem.secs.functions.SecsS07F41>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString, SecsVarBinary]


class PPGNT(DataItemBase):
    """
    Process program grant status.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+------------------------+-------------------------------------------------------+
        | Value | Description            | Constant                                              |
        +=======+========================+=======================================================+
        | 0     | OK                     | :const:`secsgem.secs.dataitems.PPGNT.OK`              |
        +-------+------------------------+-------------------------------------------------------+
        | 1     | Already have           | :const:`secsgem.secs.dataitems.PPGNT.ALREADY_HAVE`    |
        +-------+------------------------+-------------------------------------------------------+
        | 2     | No space               | :const:`secsgem.secs.dataitems.PPGNT.NO_SPACE`        |
        +-------+------------------------+-------------------------------------------------------+
        | 3     | Invalid PPID           | :const:`secsgem.secs.dataitems.PPGNT.INVALID_PPID`    |
        +-------+------------------------+-------------------------------------------------------+
        | 4     | Busy, try later        | :const:`secsgem.secs.dataitems.PPGNT.BUSY`            |
        +-------+------------------------+-------------------------------------------------------+
        | 5     | Will not accept        | :const:`secsgem.secs.dataitems.PPGNT.WILL_NOT_ACCEPT` |
        +-------+------------------------+-------------------------------------------------------+
        | 6-63  | Reserved, other errors |                                                       |
        +-------+------------------------+-------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS07F02 <secsgem.secs.functions.SecsS07F02>`
        - :class:`SecsS07F30 <secsgem.secs.functions.SecsS07F30>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    OK = 0
    ALREADY_HAVE = 1
    NO_SPACE = 2
    INVALID_PPID = 3
    BUSY = 4
    WILL_NOT_ACCEPT = 5


class PPID(DataItemBase):
    """
    Process program ID.

    :Types:
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS02F27 <secsgem.secs.functions.SecsS02F27>`
        - :class:`SecsS07F01 <secsgem.secs.functions.SecsS07F01>`
        - :class:`SecsS07F03 <secsgem.secs.functions.SecsS07F03>`
        - :class:`SecsS07F05 <secsgem.secs.functions.SecsS07F05>`
        - :class:`SecsS07F06 <secsgem.secs.functions.SecsS07F06>`
        - :class:`SecsS07F08 <secsgem.secs.functions.SecsS07F08>`
        - :class:`SecsS07F10 <secsgem.secs.functions.SecsS07F10>`
        - :class:`SecsS07F11 <secsgem.secs.functions.SecsS07F11>`
        - :class:`SecsS07F13 <secsgem.secs.functions.SecsS07F13>`
        - :class:`SecsS07F17 <secsgem.secs.functions.SecsS07F17>`
        - :class:`SecsS07F20 <secsgem.secs.functions.SecsS07F20>`
        - :class:`SecsS07F23 <secsgem.secs.functions.SecsS07F23>`
        - :class:`SecsS07F25 <secsgem.secs.functions.SecsS07F25>`
        - :class:`SecsS07F26 <secsgem.secs.functions.SecsS07F26>`
        - :class:`SecsS07F27 <secsgem.secs.functions.SecsS07F27>`
        - :class:`SecsS07F31 <secsgem.secs.functions.SecsS07F31>`
        - :class:`SecsS07F33 <secsgem.secs.functions.SecsS07F33>`
        - :class:`SecsS07F34 <secsgem.secs.functions.SecsS07F34>`
        - :class:`SecsS07F36 <secsgem.secs.functions.SecsS07F36>`
        - :class:`SecsS07F53 <secsgem.secs.functions.SecsS07F53>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarString, SecsVarBinary]
    __count__ = 120


class PRAXI(DataItemBase):
    """
    Process axis.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+----------------------------+-------------------------------------------------------+
        | Value | Description                | Constant                                              |
        +=======+============================+=======================================================+
        | 0     | Rows, top, increasing      | :const:`secsgem.secs.dataitems.PRAXI.ROWS_TOP_INCR`   |
        +-------+----------------------------+-------------------------------------------------------+
        | 1     | Rows, top, decreasing      | :const:`secsgem.secs.dataitems.PRAXI.ROWS_TOP_DECR`   |
        +-------+----------------------------+-------------------------------------------------------+
        | 2     | Rows, bottom, increasing   | :const:`secsgem.secs.dataitems.PRAXI.ROWS_BOT_INCR`   |
        +-------+----------------------------+-------------------------------------------------------+
        | 3     | Rows, bottom, decreasing   | :const:`secsgem.secs.dataitems.PRAXI.ROWS_BOT_DECR`   |
        +-------+----------------------------+-------------------------------------------------------+
        | 4     | Columns, left, increasing  | :const:`secsgem.secs.dataitems.PRAXI.COLS_LEFT_INCR`  |
        +-------+----------------------------+-------------------------------------------------------+
        | 5     | Columns, left, decreasing  | :const:`secsgem.secs.dataitems.PRAXI.COLS_LEFT_DECR`  |
        +-------+----------------------------+-------------------------------------------------------+
        | 6     | Columns, right, increasing | :const:`secsgem.secs.dataitems.PRAXI.COLS_RIGHT_INCR` |
        +-------+----------------------------+-------------------------------------------------------+
        | 7     | Columns, right, decreasing | :const:`secsgem.secs.dataitems.PRAXI.COLS_RIGHT_DECR` |
        +-------+----------------------------+-------------------------------------------------------+
        | 8-63  | Error                      |                                                       |
        +-------+----------------------------+-------------------------------------------------------+

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    ROWS_TOP_INCR = 0
    ROWS_TOP_DECR = 1
    ROWS_BOT_INCR = 2
    ROWS_BOT_DECR = 3
    COLS_LEFT_INCR = 4
    COLS_LEFT_DECR = 5
    COLS_RIGHT_INCR = 6
    COLS_RIGHT_DECR = 7


class PRDCT(DataItemBase):
    """
    Process die count.

    :Types:
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8]


class RCMD(DataItemBase):
    """
    Remote command.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`

    **Used In Function**
        - :class:`SecsS02F21 <secsgem.secs.functions.SecsS02F21>`
        - :class:`SecsS02F41 <secsgem.secs.functions.SecsS02F41>`
        - :class:`SecsS02F49 <secsgem.secs.functions.SecsS02F49>`
    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarI1, SecsVarString]


class REFP(DataItemBase):
    """
    Reference point.

    :Types:
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`

    **Used In Function**
        - :class:`SecsS01F03 <secsgem.secs.functions.SecsS01F03>`
        - :class:`SecsS01F11 <secsgem.secs.functions.SecsS01F11>`
        - :class:`SecsS01F12 <secsgem.secs.functions.SecsS01F12>`
        - :class:`SecsS02F23 <secsgem.secs.functions.SecsS02F23>`
    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8]


class ROWCT(DataItemBase):
    """
    Row count in dies.

    :Types:
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8]


class RPSEL(DataItemBase):
    """
    Reference point select.

       :Types: :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`

    """

    __type__ = SecsVarU1


class RPTID(DataItemBase):
    """
    Report ID.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F33 <secsgem.secs.functions.SecsS02F33>`
        - :class:`SecsS02F35 <secsgem.secs.functions.SecsS02F35>`
        - :class:`SecsS06F11 <secsgem.secs.functions.SecsS06F11>`
        - :class:`SecsS06F13 <secsgem.secs.functions.SecsS06F13>`
        - :class:`SecsS06F16 <secsgem.secs.functions.SecsS06F16>`
        - :class:`SecsS06F18 <secsgem.secs.functions.SecsS06F18>`
        - :class:`SecsS06F19 <secsgem.secs.functions.SecsS06F19>`
        - :class:`SecsS06F21 <secsgem.secs.functions.SecsS06F21>`
        - :class:`SecsS06F27 <secsgem.secs.functions.SecsS06F27>`
        - :class:`SecsS06F30 <secsgem.secs.functions.SecsS06F30>`
        - :class:`SecsS17F01 <secsgem.secs.functions.SecsS17F01>`
        - :class:`SecsS17F02 <secsgem.secs.functions.SecsS17F02>`
        - :class:`SecsS17F03 <secsgem.secs.functions.SecsS17F03>`
        - :class:`SecsS17F04 <secsgem.secs.functions.SecsS17F04>`
        - :class:`SecsS17F05 <secsgem.secs.functions.SecsS17F05>`
        - :class:`SecsS17F09 <secsgem.secs.functions.SecsS17F09>`
        - :class:`SecsS17F11 <secsgem.secs.functions.SecsS17F11>`
        - :class:`SecsS17F12 <secsgem.secs.functions.SecsS17F12>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString]


class RSINF(DataItemBase):
    """
    Starting location.

    :Types:
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`

    **Used In Function**
        - :class:`SecsS12F07 <secsgem.secs.functions.SecsS12F07>`
        - :class:`SecsS12F14 <secsgem.secs.functions.SecsS12F14>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8]
    __count__ = 3


class SDACK(DataItemBase):
    """
    Map setup acknowledge.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+---------------+-------------------------------------------+
        | Value | Description   | Constant                                  |
        +=======+===============+===========================================+
        | 0     | Received Data | :const:`secsgem.secs.dataitems.SDACK.ACK` |
        +-------+---------------+-------------------------------------------+
        | 1-63  | Error         |                                           |
        +-------+---------------+-------------------------------------------+

    **Used In Function**
        - :class:`SecsS12F02 <secsgem.secs.functions.SecsS12F02>`

    """

    __type__ = SecsVarBinary
    __count__ = 1

    ACK = 0


class SDBIN(DataItemBase):
    """
    Send bin information.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Values**
        +-------+---------------------------+-------------------------------------------------+
        | Value | Description               | Constant                                        |
        +=======+===========================+=================================================+
        | 0     | Send bin information      | :const:`secsgem.secs.dataitems.SDBIN.SEND`      |
        +-------+---------------------------+-------------------------------------------------+
        | 1     | Don't send bin infomation | :const:`secsgem.secs.dataitems.SDBIN.DONT_SEND` |
        +-------+---------------------------+-------------------------------------------------+
        | 2-63  | Reserved                  |                                                 |
        +-------+---------------------------+-------------------------------------------------+

    **Used In Function**
        - :class:`SecsS12F17 <secsgem.secs.functions.SecsS12F17>`
    """

    __type__ = SecsVarBinary
    __count__ = 1

    SEND = 0
    DONT_SEND = 1


class SHEAD(DataItemBase):
    """
    SECS message header.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 10

    **Used In Function**
        - :class:`SecsS09F09 <secsgem.secs.functions.SecsS09F09>`

    """

    __type__ = SecsVarBinary
    __count__ = 10


class SOFTREV(DataItemBase):
    """
    Software revision.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS01F02 <secsgem.secs.functions.SecsS01F02>`
        - :class:`SecsS01F13 <secsgem.secs.functions.SecsS01F13>`
        - :class:`SecsS01F14 <secsgem.secs.functions.SecsS01F14>`
        - :class:`SecsS07F22 <secsgem.secs.functions.SecsS07F22>`
        - :class:`SecsS07F23 <secsgem.secs.functions.SecsS07F23>`
        - :class:`SecsS07F26 <secsgem.secs.functions.SecsS07F26>`
        - :class:`SecsS07F31 <secsgem.secs.functions.SecsS07F31>`
        - :class:`SecsS07F39 <secsgem.secs.functions.SecsS07F39>`
        - :class:`SecsS07F43 <secsgem.secs.functions.SecsS07F43>`

    """

    __type__ = SecsVarString
    __count__ = 20


class STRP(DataItemBase):
    """
    Starting position.

    :Types:
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`

    **Used In Function**
        - :class:`SecsS12F09 <secsgem.secs.functions.SecsS12F09>`
        - :class:`SecsS12F16 <secsgem.secs.functions.SecsS12F16>`
    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8]
    __count__ = 2


class SV(DataItemBase):
    """
    Status variable value.

    :Types:
       - :class:`SecsVarArray <secsgem.secs.variables.SecsVarArray>`
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarBoolean <secsgem.secs.variables.SecsVarBoolean>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarF8 <secsgem.secs.variables.SecsVarF8>`
       - :class:`SecsVarF4 <secsgem.secs.variables.SecsVarF4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS01F04 <secsgem.secs.functions.SecsS01F04>`
        - :class:`SecsS06F01 <secsgem.secs.functions.SecsS06F01>`
    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarArray, SecsVarBoolean, SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2,
                        SecsVarI4, SecsVarI8, SecsVarF4, SecsVarF8, SecsVarString, SecsVarBinary]


class SVID(DataItemBase):
    """
    Status variable ID.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS01F03 <secsgem.secs.functions.SecsS01F03>`
        - :class:`SecsS01F11 <secsgem.secs.functions.SecsS01F11>`
        - :class:`SecsS01F12 <secsgem.secs.functions.SecsS01F12>`
        - :class:`SecsS02F23 <secsgem.secs.functions.SecsS02F23>`
    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString]


class SVNAME(DataItemBase):
    """
    Status variable name.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS01F12 <secsgem.secs.functions.SecsS01F12>`
    """

    __type__ = SecsVarString


class TEXT(DataItemBase):
    """
    Line of characters.

    :Types:
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS10F01 <secsgem.secs.functions.SecsS10F01>`
        - :class:`SecsS10F03 <secsgem.secs.functions.SecsS10F03>`
        - :class:`SecsS10F05 <secsgem.secs.functions.SecsS10F05>`
        - :class:`SecsS10F09 <secsgem.secs.functions.SecsS10F09>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString, SecsVarBinary]


class TID(DataItemBase):
    """
    Terminal ID.

       :Types: :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       :Length: 1

    **Used In Function**
        - :class:`SecsS10F01 <secsgem.secs.functions.SecsS10F01>`
        - :class:`SecsS10F03 <secsgem.secs.functions.SecsS10F03>`
        - :class:`SecsS10F05 <secsgem.secs.functions.SecsS10F05>`
        - :class:`SecsS10F07 <secsgem.secs.functions.SecsS10F07>`

    """

    __type__ = SecsVarBinary
    __count__ = 1


class TIME(DataItemBase):
    """
    Time of day.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS02F18 <secsgem.secs.functions.SecsS02F18>`
        - :class:`SecsS02F31 <secsgem.secs.functions.SecsS02F31>`
    """

    __type__ = SecsVarString
    __count__ = 32


class TIMESTAMP(DataItemBase):
    """
    Timestamp.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS05F09 <secsgem.secs.functions.SecsS05F09>`
        - :class:`SecsS05F11 <secsgem.secs.functions.SecsS05F11>`
        - :class:`SecsS05F15 <secsgem.secs.functions.SecsS05F15>`
        - :class:`SecsS15F41 <secsgem.secs.functions.SecsS15F41>`
        - :class:`SecsS15F44 <secsgem.secs.functions.SecsS15F44>`
        - :class:`SecsS16F05 <secsgem.secs.functions.SecsS16F05>`
        - :class:`SecsS16F07 <secsgem.secs.functions.SecsS16F07>`
        - :class:`SecsS16F09 <secsgem.secs.functions.SecsS16F09>`
    """

    __type__ = SecsVarString
    __count__ = 32


class UNITS(DataItemBase):
    """
    Units identifier.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`

    **Used In Function**
        - :class:`SecsS01F12 <secsgem.secs.functions.SecsS01F12>`
        - :class:`SecsS02F30 <secsgem.secs.functions.SecsS02F30>`
        - :class:`SecsS02F48 <secsgem.secs.functions.SecsS02F48>`
        - :class:`SecsS07F22 <secsgem.secs.functions.SecsS07F22>`
    """

    __type__ = SecsVarString


class V(DataItemBase):
    """
    Variable data.

    :Types:
       - :class:`SecsVarArray <secsgem.secs.variables.SecsVarArray>`
       - :class:`SecsVarBinary <secsgem.secs.variables.SecsVarBinary>`
       - :class:`SecsVarBoolean <secsgem.secs.variables.SecsVarBoolean>`
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarF8 <secsgem.secs.variables.SecsVarF8>`
       - :class:`SecsVarF4 <secsgem.secs.variables.SecsVarF4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS06F11 <secsgem.secs.functions.SecsS06F11>`
        - :class:`SecsS06F13 <secsgem.secs.functions.SecsS06F13>`
        - :class:`SecsS06F16 <secsgem.secs.functions.SecsS06F16>`
        - :class:`SecsS06F20 <secsgem.secs.functions.SecsS06F20>`
        - :class:`SecsS06F22 <secsgem.secs.functions.SecsS06F22>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarArray, SecsVarBoolean, SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2,
                        SecsVarI4, SecsVarI8, SecsVarF4, SecsVarF8, SecsVarString, SecsVarBinary]


class VID(DataItemBase):
    """
    Variable ID.

    :Types:
       - :class:`SecsVarString <secsgem.secs.variables.SecsVarString>`
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS02F33 <secsgem.secs.functions.SecsS02F33>`
        - :class:`SecsS02F45 <secsgem.secs.functions.SecsS02F45>`
        - :class:`SecsS02F46 <secsgem.secs.functions.SecsS02F46>`
        - :class:`SecsS02F47 <secsgem.secs.functions.SecsS02F47>`
        - :class:`SecsS02F48 <secsgem.secs.functions.SecsS02F48>`
        - :class:`SecsS06F13 <secsgem.secs.functions.SecsS06F13>`
        - :class:`SecsS06F18 <secsgem.secs.functions.SecsS06F18>`
        - :class:`SecsS06F22 <secsgem.secs.functions.SecsS06F22>`
        - :class:`SecsS17F01 <secsgem.secs.functions.SecsS17F01>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString]


class XDIES(DataItemBase):
    """
    Die size/index X-axis.

    :Types:
       - :class:`SecsVarF4 <secsgem.secs.variables.SecsVarF4>`
       - :class:`SecsVarF8 <secsgem.secs.variables.SecsVarF8>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarF4, SecsVarF8]


class XYPOS(DataItemBase):
    """
    X/Y coordinate position.

    :Types:
       - :class:`SecsVarI8 <secsgem.secs.variables.SecsVarI8>`
       - :class:`SecsVarI1 <secsgem.secs.variables.SecsVarI1>`
       - :class:`SecsVarI2 <secsgem.secs.variables.SecsVarI2>`
       - :class:`SecsVarI4 <secsgem.secs.variables.SecsVarI4>`

    **Used In Function**
        - :class:`SecsS12F11 <secsgem.secs.functions.SecsS12F11>`
        - :class:`SecsS12F18 <secsgem.secs.functions.SecsS12F18>`
    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8]
    __count__ = 2


class YDIES(DataItemBase):
    """
    Die size/index Y-axis.

    :Types:
       - :class:`SecsVarF4 <secsgem.secs.variables.SecsVarF4>`
       - :class:`SecsVarF8 <secsgem.secs.variables.SecsVarF8>`
       - :class:`SecsVarU8 <secsgem.secs.variables.SecsVarU8>`
       - :class:`SecsVarU1 <secsgem.secs.variables.SecsVarU1>`
       - :class:`SecsVarU2 <secsgem.secs.variables.SecsVarU2>`
       - :class:`SecsVarU4 <secsgem.secs.variables.SecsVarU4>`

    **Used In Function**
        - :class:`SecsS12F01 <secsgem.secs.functions.SecsS12F01>`
        - :class:`SecsS12F04 <secsgem.secs.functions.SecsS12F04>`

    """

    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarF4, SecsVarF8]


class StructureDisplayingMeta(type):
    """Meta class overriding the default __repr__ of a class."""

    def __repr__(cls):
        """Generate textual representation for an object of this class."""
        return cls.get_format()

@add_metaclass(StructureDisplayingMeta)
class SecsStreamFunction():
    """
    Secs stream and function base class.

    This class is inherited to create a stream/function class.
    To create a function specific content the class variables :attr:`_stream`, :attr:`_function`
    and :attr:`_dataFormat` must be overridden.
    """

    _stream = 0
    _function = 0

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False

    def __init__(self, value=None):
        """
        Initialize a stream function object.

        **Example**::

            class SecsS02F30(SecsStreamFunction):
                _stream = 2
                _function = 30

                _toHost = True
                _toEquipment = False

                _hasReply = False
                _isReplyRequired = False

                _isMultiBlock = True

                _dataFormat = [
                    [
                        ECID,
                        ECNAME,
                        ECMIN,
                        ECMAX,
                        ECDEF,
                        UNITS
                    ]
                ]

        :param value: set the value of stream/function parameters
        :type value: various
        """
        self.data = SecsVar.generate(self._dataFormat)

        # copy public members from private ones
        self.stream = self._stream
        self.function = self._function

        self.data_format = self._dataFormat
        self.to_host = self._toHost
        self.to_equipment = self._toEquipment

        self.has_reply = self._hasReply
        self.is_reply_required = self._isReplyRequired

        self.is_multi_block = self._isMultiBlock

        if value is not None and self.data is not None:
            self.data.set(value)

        self._object_intitialized = True

    def __repr__(self):
        """Generate textual representation for an object of this class."""
        function = "S{0}F{1}".format(self.stream, self.function)
        if self.data is None:
            return "{}{} .".format(function, " W" if self._isReplyRequired else "")
        data = "{}".format(self.data.__repr__())

        return "{}{}\n{} .".format(
            function,
            " W" if self._isReplyRequired else "",
            indent_block(data))

    def __getitem__(self, key):
        """Get an item using the indexer operator."""
        return self.data[key]

    def __setitem__(self, key, item):
        """Set an item using the indexer operator."""
        self.data[key] = item

    def __len__(self):
        """Get the lenth."""
        return len(self.data)

    def __getattr__(self, item):
        """Get an item as object member."""
        return self.data.__getattr__(item)

    def __setattr__(self, item, value):
        """Set an item as object member."""
        if '_object_intitialized' not in self.__dict__:
            return dict.__setattr__(self, item, value)

        if item in self.data.data:
            return self.data.__setattr__(item, value)

        return None

    def append(self, data):
        """
        Append data to list, if stream/function parameter is a list.

        :param data: list item to add
        :type data: various
        """
        if hasattr(self.data, 'append') and callable(self.data.append):
            self.data.append(data)
        else:
            raise AttributeError(
                "class {} has no attribute 'append'".format(self.__class__.__name__))

    def encode(self):
        """
        Generates the encoded hsms data of the stream/function parameter.

        :returns: encoded data
        :rtype: string
        """
        if self.data is None:
            return b""

        return self.data.encode()

    def decode(self, data):
        """
        Updates stream/function parameter data from the passed data.

        :param data: encoded data
        :type data: string
        """
        if self.data is not None:
            self.data.decode(data)

    def set(self, value):
        """
        Updates the value of the stream/function parameter.

        :param value: new value for the parameter
        :type value: various
        """
        self.data.set(value)

    def get(self):
        """
        Gets the current value of the stream/function parameter.

        :returns: current parameter value
        :rtype: various
        """
        return self.data.get()

    @classmethod
    def get_format(cls):
        """
        Gets the format of the function.

        :returns: returns the string representation of the function
        :rtype: string
        """
        if cls._dataFormat is not None:
            return SecsVar.get_format(cls._dataFormat)

        return "Header only"


class SecsS00F00(SecsStreamFunction):
    """
    Hsms communication.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS00F00
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS00F00()
        S0F0 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 0
    _function = 0

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS01F00(SecsStreamFunction):
    """
    abort transaction stream 1.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS01F00
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F00()
        S1F0 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 1
    _function = 0

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS01F01(SecsStreamFunction):
    """
    are you online - request.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS01F01
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F01()
        S1F1 W .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 1
    _function = 1

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS01F02(SecsStreamFunction):
    """
    on line data.

    .. caution::

        This Stream/function has different structures depending on the source.
        If it is sent from the eqipment side it has the structure below, if it
        is sent from the host it is an empty list.
        Be sure to fill the array accordingly.

    **Structure E->H**::

        {
            MDLN: A[20]
            SOFTREV: A[20]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F02(['secsgem', '0.0.6']) # E->H
        S1F2
          <L [2]
            <A "secsgem">
            <A "0.0.6">
          > .
        >>> secsgem.SecsS01F02() #H->E
        S1F2
          <L> .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 1
    _function = 2

    _dataFormat = [MDLN]

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS01F03(SecsStreamFunction):
    """
    Selected equipment status - request.

    **Data Items**

    - :class:`SVID <secsgem.secs.dataitems.SVID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS01F03
        [
            SVID: U1/U2/U4/U8/I1/I2/I4/I8/A
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F03([1, "1337", 12])
        S1F3 W
          <L [3]
            <U1 1 >
            <A "1337">
            <U1 12 >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 1
    _function = 3

    _dataFormat = [SVID]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS01F04(SecsStreamFunction):
    """
    selected equipment status - data.

    **Data Items**

    - :class:`SV <secsgem.secs.dataitems.SV>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS01F04
        [
            SV: L/BOOLEAN/U1/U2/U4/U8/I1/I2/I4/I8/F4/F8/A/B
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F04([secsgem.SecsVarU1(1), "text", secsgem.SecsVarU4(1337)])
        S1F4
          <L [3]
            <U1 1 >
            <A "text">
            <U4 1337 >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 1
    _function = 4

    _dataFormat = [SV]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS01F11(SecsStreamFunction):
    """
    status variable namelist - request.

    **Data Items**

    - :class:`SVID <secsgem.secs.dataitems.SVID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS01F11
        [
            SVID: U1/U2/U4/U8/I1/I2/I4/I8/A
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F11([1, 1337])
        S1F11 W
          <L [2]
            <U1 1 >
            <U2 1337 >
          > .

    An empty list will return all available status variables.

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 1
    _function = 11

    _dataFormat = [SVID]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS01F12(SecsStreamFunction):
    """
    status variable namelist - reply.

    **Data Items**

    - :class:`SVID <secsgem.secs.dataitems.SVID>`
    - :class:`SVNAME <secsgem.secs.dataitems.SVNAME>`
    - :class:`UNITS <secsgem.secs.dataitems.UNITS>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS01F12
        [
            {
                SVID: U1/U2/U4/U8/I1/I2/I4/I8/A
                SVNAME: A
                UNITS: A
            }
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F12([{"SVID": 1, "SVNAME": "SV1", "UNITS": "mm"},
        ...     {"SVID": 1337, "SVNAME": "SV2", "UNITS": ""}])
        S1F12
          <L [2]
            <L [3]
              <U1 1 >
              <A "SV1">
              <A "mm">
            >
            <L [3]
              <U2 1337 >
              <A "SV2">
              <A>
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 1
    _function = 12

    _dataFormat = [
        [
            SVID,
            SVNAME,
            UNITS
        ]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS01F13(SecsStreamFunction):
    """
    establish communication - request.

    .. caution::

        This Stream/function has different structures depending on the source.
        If it is sent from the eqipment side it has the structure below,
        if it is sent from the host it is an empty list.
        Be sure to fill the array accordingly.

    **Structure E->H**::

        {
            MDLN: A[20]
            SOFTREV: A[20]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F13(['secsgem', '0.0.6']) # E->H
        S1F13 W
          <L [2]
            <A "secsgem">
            <A "0.0.6">
          > .
        >>> secsgem.SecsS01F13() #H->E
        S1F13 W
          <L> .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 1
    _function = 13

    _dataFormat = [MDLN]

    _toHost = True
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS01F14(SecsStreamFunction):
    """
    establish communication - acknowledge.

    .. caution::

        This Stream/function has different structures depending on the source.
        See structure definition below for details.
        Be sure to fill the array accordingly.

    **Data Items**

    - :class:`COMMACK <secsgem.secs.dataitems.COMMACK>`

    **Structure E->H**::

        {
            COMMACK: B[1]
            DATA: {
                MDLN: A[20]
                SOFTREV: A[20]
            }
        }

    **Structure H->E**::

        {
            COMMACK: B[1]
            DATA: []
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F14({"COMMACK": secsgem.COMMACK.ACCEPTED, "MDLN": ["secsgem", "0.0.6"]})
        S1F14
          <L [2]
            <B 0x0>
            <L [2]
              <A "secsgem">
              <A "0.0.6">
            >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 1
    _function = 14

    _dataFormat = [
        COMMACK,
        [MDLN]
    ]

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS01F15(SecsStreamFunction):
    """
    request offline.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS01F15
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F15()
        S1F15 W .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 1
    _function = 15

    _dataFormat = None

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS01F16(SecsStreamFunction):
    """
    offline acknowledge.

    **Data Items**

    - :class:`OFLACK <secsgem.secs.dataitems.OFLACK>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS01F16
        OFLACK: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F16(secsgem.OFLACK.ACK)
        S1F16
          <B 0x0> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 1
    _function = 16

    _dataFormat = OFLACK

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS01F17(SecsStreamFunction):
    """
    request online.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS01F17
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F17()
        S1F17 W .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 1
    _function = 17

    _dataFormat = None

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS01F18(SecsStreamFunction):
    """
    online acknowledge.

    **Data Items**

    - :class:`ONLACK <secsgem.secs.dataitems.ONLACK>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS01F18
        ONLACK: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS01F18(secsgem.ONLACK.ALREADY_ON)
        S1F18
          <B 0x2> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 1
    _function = 18

    _dataFormat = ONLACK

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS02F00(SecsStreamFunction):
    """
    abort transaction stream 2.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F00
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F00()
        S2F0 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 2
    _function = 0

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS02F13(SecsStreamFunction):
    """
    equipment constant - request.

    **Data Items**

    - :class:`ECID <secsgem.secs.dataitems.ECID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F13
        [
            ECID: U1/U2/U4/U8/I1/I2/I4/I8/A
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F13([1, 1337])
        S2F13 W
          <L [2]
            <U1 1 >
            <U2 1337 >
          > .

    An empty list will return all available equipment constants.

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 2
    _function = 13

    _dataFormat = [ECID]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS02F14(SecsStreamFunction):
    """
    equipment constant - data.

    **Data Items**

    - :class:`ECV <secsgem.secs.dataitems.ECV>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F14
        [
            ECV: L/BOOLEAN/I8/I1/I2/I4/F8/F4/U8/U1/U2/U4/A/B
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F14([secsgem.SecsVarU1(1), "text"])
        S2F14
          <L [2]
            <U1 1 >
            <A "text">
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 2
    _function = 14

    _dataFormat = [ECV]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS02F15(SecsStreamFunction):
    """
    new equipment constant - send.

    **Data Items**

    - :class:`ECID <secsgem.secs.dataitems.ECID>`
    - :class:`ECV <secsgem.secs.dataitems.ECV>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F15
        [
            {
                ECID: U1/U2/U4/U8/I1/I2/I4/I8/A
                ECV: L/BOOLEAN/I8/I1/I2/I4/F8/F4/U8/U1/U2/U4/A/B
            }
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F15([{"ECID": 1, "ECV": secsgem.SecsVarU4(10)}, {"ECID": "1337", "ECV": "text"}])
        S2F15 W
          <L [2]
            <L [2]
              <U1 1 >
              <U4 10 >
            >
            <L [2]
              <A "1337">
              <A "text">
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 2
    _function = 15

    _dataFormat = [
        [
            ECID,
            ECV
        ]
    ]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS02F16(SecsStreamFunction):
    """
    new equipment constant - acknowledge.

    **Data Items**

    - :class:`EAC <secsgem.secs.dataitems.EAC>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F16
        EAC: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F16(secsgem.EAC.BUSY)
        S2F16
          <B 0x2> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 2
    _function = 16

    _dataFormat = EAC

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS02F17(SecsStreamFunction):
    """
    date and time - request.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F17
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F17()
        S2F17 W .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 2
    _function = 17

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS02F18(SecsStreamFunction):
    """
    date and time - data.

    **Data Items**

    - :class:`TIME <secsgem.secs.dataitems.TIME>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F18
        TIME: A[32]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F18("160816205942")
        S2F18
          <A "160816205942"> .

    :param value: parameters for this function (see example)
    :type value: ASCII string
    """

    _stream = 2
    _function = 18

    _dataFormat = TIME

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS02F29(SecsStreamFunction):
    """
    equipment constant namelist - request.

    **Data Items**

    - :class:`ECID <secsgem.secs.dataitems.ECID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F29
        [
            ECID: U1/U2/U4/U8/I1/I2/I4/I8/A
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F29([1, 1337])
        S2F29 W
          <L [2]
            <U1 1 >
            <U2 1337 >
          > .

    An empty list will return all available equipment constants.

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 2
    _function = 29

    _dataFormat = [ECID]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS02F30(SecsStreamFunction):
    """
    equipment constant namelist.

    **Data Items**

    - :class:`ECID <secsgem.secs.dataitems.ECID>`
    - :class:`ECNAME <secsgem.secs.dataitems.ECNAME>`
    - :class:`ECMIN <secsgem.secs.dataitems.ECMIN>`
    - :class:`ECMAX <secsgem.secs.dataitems.ECMAX>`
    - :class:`ECDEF <secsgem.secs.dataitems.ECDEF>`
    - :class:`UNITS <secsgem.secs.dataitems.UNITS>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F30
        [
            {
                ECID: U1/U2/U4/U8/I1/I2/I4/I8/A
                ECNAME: A
                ECMIN: BOOLEAN/I8/I1/I2/I4/F8/F4/U8/U1/U2/U4/A/B
                ECMAX: BOOLEAN/I8/I1/I2/I4/F8/F4/U8/U1/U2/U4/A/B
                ECDEF: BOOLEAN/I8/I1/I2/I4/F8/F4/U8/U1/U2/U4/A/B
                UNITS: A
            }
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F30([ \
            {"ECID": 1, "ECNAME": "EC1", "ECMIN": secsgem.SecsVarU1(0), "ECMAX": secsgem.SecsVarU1(100), \
                "ECDEF": secsgem.SecsVarU1(50), "UNITS": "mm"}, \
            {"ECID": 1337, "ECNAME": "EC2", "ECMIN": "", "ECMAX": "", "ECDEF": "", "UNITS": ""}])
        S2F30
          <L [2]
            <L [6]
              <U1 1 >
              <A "EC1">
              <U1 0 >
              <U1 100 >
              <U1 50 >
              <A "mm">
            >
            <L [6]
              <U2 1337 >
              <A "EC2">
              <A>
              <A>
              <A>
              <A>
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 2
    _function = 30

    _dataFormat = [
        [
            ECID,
            ECNAME,
            ECMIN,
            ECMAX,
            ECDEF,
            UNITS
        ]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS02F33(SecsStreamFunction):
    """
    define report.

    **Data Items**

    - :class:`DATAID <secsgem.secs.dataitems.DATAID>`
    - :class:`RPTID <secsgem.secs.dataitems.RPTID>`
    - :class:`VID <secsgem.secs.dataitems.VID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F33
        {
            DATAID: U1/U2/U4/U8/I1/I2/I4/I8/A
            DATA: [
                {
                    RPTID: U1/U2/U4/U8/I1/I2/I4/I8/A
                    VID: [
                        DATA: U1/U2/U4/U8/I1/I2/I4/I8/A
                        ...
                    ]
                }
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F33({"DATAID": 1, "DATA": [{"RPTID": 1000, "VID": [12, 1337]}, \
{"RPTID": 1001, "VID": [1, 2355]}]})
        S2F33 W
          <L [2]
            <U1 1 >
            <L [2]
              <L [2]
                <U2 1000 >
                <L [2]
                  <U1 12 >
                  <U2 1337 >
                >
              >
              <L [2]
                <U2 1001 >
                <L [2]
                  <U1 1 >
                  <U2 2355 >
                >
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 2
    _function = 33

    _dataFormat = [
        DATAID,
        [
            [
                RPTID,
                [VID]
            ]
        ]
    ]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = True


class SecsS02F34(SecsStreamFunction):
    """
    define report - acknowledge.

    **Data Items**

    - :class:`DRACK <secsgem.secs.dataitems.DRACK>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F34
        DRACK: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F34(secsgem.DRACK.INVALID_FORMAT)
        S2F34
          <B 0x2> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 2
    _function = 34

    _dataFormat = DRACK

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS02F35(SecsStreamFunction):
    """
    link event report.

    **Data Items**

    - :class:`DATAID <secsgem.secs.dataitems.DATAID>`
    - :class:`CEID <secsgem.secs.dataitems.CEID>`
    - :class:`RPTID <secsgem.secs.dataitems.RPTID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F35
        {
            DATAID: U1/U2/U4/U8/I1/I2/I4/I8/A
            DATA: [
                {
                    CEID: U1/U2/U4/U8/I1/I2/I4/I8/A
                    RPTID: [
                        DATA: U1/U2/U4/U8/I1/I2/I4/I8/A
                        ...
                    ]
                }
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F35({"DATAID": 1, "DATA": [{"CEID": 1337, "RPTID": [1000, 1001]}]})
        S2F35 W
          <L [2]
            <U1 1 >
            <L [1]
              <L [2]
                <U2 1337 >
                <L [2]
                  <U2 1000 >
                  <U2 1001 >
                >
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 2
    _function = 35

    _dataFormat = [
        DATAID,
        [
            [
                CEID,
                [RPTID]
            ]
        ]
    ]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = True


class SecsS02F36(SecsStreamFunction):
    """
    link event report - acknowledge.

    **Data Items**

    - :class:`LRACK <secsgem.secs.dataitems.LRACK>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F36
        LRACK: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F36(secsgem.LRACK.CEID_UNKNOWN)
        S2F36
          <B 0x4> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 2
    _function = 36

    _dataFormat = LRACK

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS02F37(SecsStreamFunction):
    """
    en-/disable event report.

    **Data Items**

    - :class:`CEED <secsgem.secs.dataitems.CEED>`
    - :class:`CEID <secsgem.secs.dataitems.CEID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F37
        {
            CEED: BOOLEAN[1]
            CEID: [
                DATA: U1/U2/U4/U8/I1/I2/I4/I8/A
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F37({"CEED": True, "CEID": [1337]})
        S2F37 W
          <L [2]
            <BOOLEAN True >
            <L [1]
              <U2 1337 >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 2
    _function = 37

    _dataFormat = [
        CEED,
        [CEID]
    ]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS02F38(SecsStreamFunction):
    """
    en-/disable event report - acknowledge.

    **Data Items**

    - :class:`ERACK <secsgem.secs.dataitems.ERACK>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F38
        ERACK: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F38(secsgem.ERACK.CEID_UNKNOWN)
        S2F38
          <B 0x1> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 2
    _function = 38

    _dataFormat = ERACK

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS02F41(SecsStreamFunction):
    """
    host command - send.

    **Data Items**

    - :class:`RCMD <secsgem.secs.dataitems.RCMD>`
    - :class:`CPNAME <secsgem.secs.dataitems.CPNAME>`
    - :class:`CPVAL <secsgem.secs.dataitems.CPVAL>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F41
        {
            RCMD: U1/I1/A
            PARAMS: [
                {
                    CPNAME: U1/U2/U4/U8/I1/I2/I4/I8/A
                    CPVAL: BOOLEAN/U1/U2/U4/U8/I1/I2/I4/I8/A/B
                }
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F41({"RCMD": "COMMAND", "PARAMS": [{"CPNAME": "PARAM1", "CPVAL": "VAL1"}, \
{"CPNAME": "PARAM2", "CPVAL": "VAL2"}]})
        S2F41 W
          <L [2]
            <A "COMMAND">
            <L [2]
              <L [2]
                <A "PARAM1">
                <A "VAL1">
              >
              <L [2]
                <A "PARAM2">
                <A "VAL2">
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 2
    _function = 41

    _dataFormat = [
        RCMD,
        [
            [
                "PARAMS",   # name of the list
                CPNAME,
                CPVAL
            ]
        ]
    ]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS02F42(SecsStreamFunction):
    """
    host command - acknowledge.

    **Data Items**

    - :class:`HCACK <secsgem.secs.dataitems.HCACK>`
    - :class:`CPNAME <secsgem.secs.dataitems.CPNAME>`
    - :class:`CPACK <secsgem.secs.dataitems.CPACK>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS02F42
        {
            HCACK: B[1]
            PARAMS: [
                {
                    CPNAME: U1/U2/U4/U8/I1/I2/I4/I8/A
                    CPACK: B[1]
                }
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS02F42({ \
            "HCACK": secsgem.HCACK.INVALID_COMMAND, \
            "PARAMS": [ \
                {"CPNAME": "PARAM1", "CPACK": secsgem.CPACK.CPVAL_ILLEGAL_VALUE}, \
                {"CPNAME": "PARAM2", "CPACK": secsgem.CPACK.CPVAL_ILLEGAL_FORMAT}]})
        S2F42
          <L [2]
            <B 0x1>
            <L [2]
              <L [2]
                <A "PARAM1">
                <B 0x2>
              >
              <L [2]
                <A "PARAM2">
                <B 0x3>
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 2
    _function = 42

    _dataFormat = [
        HCACK,
        [
            [
                "PARAMS",   # name of the list
                CPNAME,
                CPACK
            ]
        ]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F00(SecsStreamFunction):
    """
    abort transaction stream 5.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F00
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F00()
        S5F0 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 5
    _function = 0

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F01(SecsStreamFunction):
    """
    alarm report - send.

    **Data Items**

    - :class:`ALCD <secsgem.secs.dataitems.ALCD>`
    - :class:`ALID <secsgem.secs.dataitems.ALID>`
    - :class:`ALTX <secsgem.secs.dataitems.ALTX>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F01
        {
            ALCD: B[1]
            ALID: U1/U2/U4/U8/I1/I2/I4/I8
            ALTX: A[120]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F01({"ALCD": secsgem.ALCD.PERSONAL_SAFETY | \
secsgem.ALCD.ALARM_SET, "ALID": 100, "ALTX": "text"})
        S5F1
          <L [3]
            <B 0x81>
            <U1 100 >
            <A "text">
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 1

    _dataFormat = [
        ALCD,
        ALID,
        ALTX
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F02(SecsStreamFunction):
    """
    alarm report - acknowledge.

    **Data Items**

    - :class:`ACKC5 <secsgem.secs.dataitems.ACKC5>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F02
        ACKC5: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F02(secsgem.ACKC5.ACCEPTED)
        S5F2
          <B 0x0> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 5
    _function = 2

    _dataFormat = ACKC5

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F03(SecsStreamFunction):
    """
    en-/disable alarm - send.

    **Data Items**

    - :class:`ALED <secsgem.secs.dataitems.ALED>`
    - :class:`ALID <secsgem.secs.dataitems.ALID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F03
        {
            ALED: B[1]
            ALID: U1/U2/U4/U8/I1/I2/I4/I8
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F03({"ALED": secsgem.ALED.ENABLE, "ALID": 100})
        S5F3
          <L [2]
            <B 0x80>
            <U1 100 >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 3

    _dataFormat = [
        ALED,
        ALID
    ]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F04(SecsStreamFunction):
    """
    en-/disable alarm - acknowledge.

    **Data Items**

    - :class:`ACKC5 <secsgem.secs.dataitems.ACKC5>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F04
        ACKC5: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F04(secsgem.ACKC5.ACCEPTED)
        S5F4
          <B 0x0> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 5
    _function = 4

    _dataFormat = ACKC5

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F05(SecsStreamFunction):
    """
    list alarms - request.

    **Data Items**

    - :class:`ALID <secsgem.secs.dataitems.ALID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F05
        [
            ALID: U1/U2/U4/U8/I1/I2/I4/I8
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F05([100, 200])
        S5F5 W
          <L [2]
            <U1 100 >
            <U1 200 >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 5

    _dataFormat = [ALID]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS05F06(SecsStreamFunction):
    """
    list alarms - data.

    **Data Items**

    - :class:`ALCD <secsgem.secs.dataitems.ALCD>`
    - :class:`ALID <secsgem.secs.dataitems.ALID>`
    - :class:`ALTX <secsgem.secs.dataitems.ALTX>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F06
        [
            {
                ALCD: B[1]
                ALID: U1/U2/U4/U8/I1/I2/I4/I8
                ALTX: A[120]
            }
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F06([{"ALCD": secsgem.ALCD.PERSONAL_SAFETY | \
secsgem.ALCD.ALARM_SET, "ALID": 100, "ALTX": "text"}])
        S5F6
          <L [1]
            <L [3]
              <B 0x81>
              <U1 100 >
              <A "text">
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 6

    _dataFormat = [[
        ALCD,
        ALID,
        ALTX
    ]]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS05F07(SecsStreamFunction):
    """
    list enabled alarms - request.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F07
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F07()
        S5F7 W .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 7

    _dataFormat = None

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS05F08(SecsStreamFunction):
    """
    list enabled alarms - data.

    **Data Items**

    - :class:`ALCD <secsgem.secs.dataitems.ALCD>`
    - :class:`ALID <secsgem.secs.dataitems.ALID>`
    - :class:`ALTX <secsgem.secs.dataitems.ALTX>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F08
        [
            {
                ALCD: B[1]
                ALID: U1/U2/U4/U8/I1/I2/I4/I8
                ALTX: A[120]
            }
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F08([{"ALCD": secsgem.ALCD.PERSONAL_SAFETY | \
secsgem.ALCD.ALARM_SET, "ALID": 100, "ALTX": "text"}])
        S5F8
          <L [1]
            <L [3]
              <B 0x81>
              <U1 100 >
              <A "text">
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 8

    _dataFormat = [[
        ALCD,
        ALID,
        ALTX
    ]]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS05F09(SecsStreamFunction):
    """
    exception post - notify.

    **Data Items**

    - :class:`TIMESTAMP <secsgem.secs.dataitems.TIMESTAMP>`
    - :class:`EXID <secsgem.secs.dataitems.EXID>`
    - :class:`EXTYPE <secsgem.secs.dataitems.EXTYPE>`
    - :class:`EXMESSAGE <secsgem.secs.dataitems.EXMESSAGE>`
    - :class:`EXRECVRA <secsgem.secs.dataitems.EXRECVRA>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F09
        {
            TIMESTAMP: A[32]
            EXID: A[20]
            EXTYPE: A
            EXMESSAGE: A
            EXRECVRA: [
                DATA: A[40]
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F09({ \
            "TIMESTAMP": "161006221500", \
            "EXID": "EX123", \
            "EXTYPE": "ALARM", \
            "EXMESSAGE": "Exception", \
            "EXRECVRA": ["EXRECVRA1", "EXRECVRA2"] })
        S5F9
          <L [5]
            <A "161006221500">
            <A "EX123">
            <A "ALARM">
            <A "Exception">
            <L [2]
              <A "EXRECVRA1">
              <A "EXRECVRA2">
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 9

    _dataFormat = [
        TIMESTAMP,
        EXID,
        EXTYPE,
        EXMESSAGE,
        [EXRECVRA]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F10(SecsStreamFunction):
    """
    exception post - confirm.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F10
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F10()
        S5F10 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 5
    _function = 10

    _dataFormat = None

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F11(SecsStreamFunction):
    """
    exception clear - notify.

    **Data Items**

    - :class:`TIMESTAMP <secsgem.secs.dataitems.TIMESTAMP>`
    - :class:`EXID <secsgem.secs.dataitems.EXID>`
    - :class:`EXTYPE <secsgem.secs.dataitems.EXTYPE>`
    - :class:`EXMESSAGE <secsgem.secs.dataitems.EXMESSAGE>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F11
        {
            TIMESTAMP: A[32]
            EXID: A[20]
            EXTYPE: A
            EXMESSAGE: A
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F11({"TIMESTAMP": "161006221500", "EXID": "EX123", "EXTYPE": "ALARM", \
"EXMESSAGE": "Exception"})
        S5F11
          <L [4]
            <A "161006221500">
            <A "EX123">
            <A "ALARM">
            <A "Exception">
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 11

    _dataFormat = [
        TIMESTAMP,
        EXID,
        EXTYPE,
        EXMESSAGE,
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F12(SecsStreamFunction):
    """
    exception clear - confirm.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F12
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F12()
        S5F12 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 5
    _function = 12

    _dataFormat = None

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F13(SecsStreamFunction):
    """
    exception recover - request.

    **Data Items**

    - :class:`EXID <secsgem.secs.dataitems.EXID>`
    - :class:`EXRECVRA <secsgem.secs.dataitems.EXRECVRA>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F13
        {
            EXID: A[20]
            EXRECVRA: A[40]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F13({"EXID": "EX123", "EXRECVRA": "EXRECVRA2"})
        S5F13 W
          <L [2]
            <A "EX123">
            <A "EXRECVRA2">
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 13

    _dataFormat = [
        EXID,
        EXRECVRA
    ]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS05F14(SecsStreamFunction):
    """
    exception recover - acknowledge.

    **Data Items**

    - :class:`EXID <secsgem.secs.dataitems.EXID>`
    - :class:`ACKA <secsgem.secs.dataitems.ACKA>`
    - :class:`ERRCODE <secsgem.secs.dataitems.ERRCODE>`
    - :class:`ERRTEXT <secsgem.secs.dataitems.ERRTEXT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F14
        {
            EXID: A[20]
            DATA: {
                ACKA: BOOLEAN[1]
                DATA: {
                    ERRCODE: I1/I2/I4/I8
                    ERRTEXT: A[120]
                }
            }
        }

    **Example**::
        >>> import secsgem
        >>> secsgem.SecsS05F14({"EXID": "EX123", "DATA": {"ACKA": False, "DATA": {"ERRCODE": 10, "ERRTEXT": "Error"}}})
        S5F14
          <L [2]
            <A "EX123">
            <L [2]
              <BOOLEAN False >
              <L [2]
                <I1 10 >
                <A "Error">
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 14

    _dataFormat = [
        EXID,
        [
            ACKA,
            [
                ERRCODE,
                ERRTEXT
            ]
        ]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F15(SecsStreamFunction):
    """
    exception recover complete - notify.

    **Data Items**

    - :class:`TIMESTAMP <secsgem.secs.dataitems.TIMESTAMP>`
    - :class:`EXID <secsgem.secs.dataitems.EXID>`
    - :class:`ACKA <secsgem.secs.dataitems.ACKA>`
    - :class:`ERRCODE <secsgem.secs.dataitems.ERRCODE>`
    - :class:`ERRTEXT <secsgem.secs.dataitems.ERRTEXT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F15
        {
            TIMESTAMP: A[32]
            EXID: A[20]
            DATA: {
                ACKA: BOOLEAN[1]
                DATA: {
                    ERRCODE: I1/I2/I4/I8
                    ERRTEXT: A[120]
                }
            }
        }

    **Example**::
        >>> import secsgem
        >>> secsgem.SecsS05F15({"TIMESTAMP": "161006221500", "EXID": "EX123", "DATA": \
{"ACKA": False, "DATA": {"ERRCODE": 10, "ERRTEXT": "Error"}}})
        S5F15
          <L [3]
            <A "161006221500">
            <A "EX123">
            <L [2]
              <BOOLEAN False >
              <L [2]
                <I1 10 >
                <A "Error">
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 15

    _dataFormat = [
        TIMESTAMP,
        EXID,
        [
            ACKA,
            [
                ERRCODE,
                ERRTEXT
            ]
        ]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F16(SecsStreamFunction):
    """
    exception recover complete - confirm.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F16
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F16()
        S5F16 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 5
    _function = 16

    _dataFormat = None

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS05F17(SecsStreamFunction):
    """
    exception recover abort - request.

    **Data Items**

    - :class:`EXID <secsgem.secs.dataitems.EXID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F17
        EXID: A[20]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS05F17("EX123")
        S5F17 W
          <A "EX123"> .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 17

    _dataFormat = EXID

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS05F18(SecsStreamFunction):
    """
    exception recover abort - acknowledge.

    **Data Items**

    - :class:`EXID <secsgem.secs.dataitems.EXID>`
    - :class:`ACKA <secsgem.secs.dataitems.ACKA>`
    - :class:`ERRCODE <secsgem.secs.dataitems.ERRCODE>`
    - :class:`ERRTEXT <secsgem.secs.dataitems.ERRTEXT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS05F18
        {
            EXID: A[20]
            DATA: {
                ACKA: BOOLEAN[1]
                DATA: {
                    ERRCODE: I1/I2/I4/I8
                    ERRTEXT: A[120]
                }
            }
        }

    **Example**::
        >>> import secsgem
        >>> secsgem.SecsS05F18({"EXID": "EX123", "DATA": {"ACKA": False, "DATA": {"ERRCODE": 10, "ERRTEXT": "Error"}}})
        S5F18
          <L [2]
            <A "EX123">
            <L [2]
              <BOOLEAN False >
              <L [2]
                <I1 10 >
                <A "Error">
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 5
    _function = 18

    _dataFormat = [
        EXID,
        [
            ACKA,
            [
                ERRCODE,
                ERRTEXT
            ]
        ]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS06F00(SecsStreamFunction):
    """
    abort transaction stream 6.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F00
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F00()
        S6F0 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 6
    _function = 0

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS06F05(SecsStreamFunction):
    """
    multi block data inquiry.

    **Data Items**

    - :class:`DATAID <secsgem.secs.dataitems.DATAID>`
    - :class:`DATALENGTH <secsgem.secs.dataitems.DATALENGTH>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F05
        {
            DATAID: U1/U2/U4/U8/I1/I2/I4/I8/A
            DATALENGTH: U1/U2/U4/U8/I1/I2/I4/I8
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F05({"DATAID": 1, "DATALENGTH": 1337})
        S6F5 W
          <L [2]
            <U1 1 >
            <U2 1337 >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 6
    _function = 5

    _dataFormat = [
        DATAID,
        DATALENGTH
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS06F06(SecsStreamFunction):
    """
    multi block data grant.

    **Data Items**

    - :class:`GRANT6 <secsgem.secs.dataitems.GRANT6>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F06
        GRANT6: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F06(secsgem.GRANT6.BUSY)
        S6F6
          <B 0x1> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 6
    _function = 6

    _dataFormat = GRANT6

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS06F07(SecsStreamFunction):
    """
    data transfer request.

    **Data Items**

    - :class:`DATAID <secsgem.secs.dataitems.DATAID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F07
        DATAID: U1/U2/U4/U8/I1/I2/I4/I8/A

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F07(1)
        S6F7 W
          <U1 1 > .

    :param value: parameters for this function (see example)
    :type value: integer
    """

    _stream = 6
    _function = 7

    _dataFormat = DATAID

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS06F08(SecsStreamFunction):
    """
    data transfer data.

    **Data Items**

    - :class:`DATAID <secsgem.secs.dataitems.DATAID>`
    - :class:`CEID <secsgem.secs.dataitems.CEID>`
    - :class:`DSID <secsgem.secs.dataitems.DSID>`
    - :class:`DVNAME <secsgem.secs.dataitems.DVNAME>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F08
        {
            DATAID: U1/U2/U4/U8/I1/I2/I4/I8/A
            CEID: U1/U2/U4/U8/I1/I2/I4/I8/A
            DS: [
                {
                    DSID: U1/U2/U4/U8/I1/I2/I4/I8/A
                    DV: [
                        {
                            DVNAME: U1/U2/U4/U8/I1/I2/I4/I8/A
                            DVVAL: L/BOOLEAN/U1/U2/U4/U8/I1/I2/I4/I8/F4/F8/A/B
                        }
                        ...
                    ]
                }
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F08({ \
            "DATAID": 1, \
            "CEID": 1337, \
            "DS": [{ \
                "DSID": 1000, \
                "DV": [ \
                    {"DVNAME": "VAR1", "DVVAL": "VAR"}, \
                    {"DVNAME": "VAR2", "DVVAL": secsgem.SecsVarU4(100)}]}]})
        S6F8
          <L [3]
            <U1 1 >
            <U2 1337 >
            <L [1]
              <L [2]
                <U2 1000 >
                <L [2]
                  <L [2]
                    <A "VAR1">
                    <A "VAR">
                  >
                  <L [2]
                    <A "VAR2">
                    <U4 100 >
                  >
                >
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 6
    _function = 8

    _dataFormat = [
        DATAID,
        CEID,
        [
            [
                "DS",   # name of the list
                DSID,
                [
                    [
                        "DV",   # name of the list
                        DVNAME,
                        DVVAL
                    ]
                ]
            ]
        ]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS06F11(SecsStreamFunction):
    """
    event report.

    **Data Items**

    - :class:`DATAID <secsgem.secs.dataitems.DATAID>`
    - :class:`CEID <secsgem.secs.dataitems.CEID>`
    - :class:`RPTID <secsgem.secs.dataitems.RPTID>`
    - :class:`V <secsgem.secs.dataitems.V>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F11
        {
            DATAID: U1/U2/U4/U8/I1/I2/I4/I8/A
            CEID: U1/U2/U4/U8/I1/I2/I4/I8/A
            RPT: [
                {
                    RPTID: U1/U2/U4/U8/I1/I2/I4/I8/A
                    V: [
                        DATA: L/BOOLEAN/U1/U2/U4/U8/I1/I2/I4/I8/F4/F8/A/B
                        ...
                    ]
                }
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F11({"DATAID": 1, "CEID": 1337, "RPT": [{"RPTID": 1000, "V": \
["VAR", secsgem.SecsVarU4(100)]}]})
        S6F11 W
          <L [3]
            <U1 1 >
            <U2 1337 >
            <L [1]
              <L [2]
                <U2 1000 >
                <L [2]
                  <A "VAR">
                  <U4 100 >
                >
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 6
    _function = 11

    _dataFormat = [
        DATAID,
        CEID,
        [
            [
                "RPT",   # name of the list
                RPTID,
                [V]
            ]
        ]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = True


class SecsS06F12(SecsStreamFunction):
    """
    event report - acknowledge.

    **Data Items**

    - :class:`ACKC6 <secsgem.secs.dataitems.ACKC6>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F12
        ACKC6: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F12(secsgem.ACKC6.ACCEPTED)
        S6F12
          <B 0x0> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 6
    _function = 12

    _dataFormat = ACKC6

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS06F15(SecsStreamFunction):
    """
    event report request.

    **Data Items**

    - :class:`CEID <secsgem.secs.dataitems.CEID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F15
        CEID: U1/U2/U4/U8/I1/I2/I4/I8/A

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F15(1337)
        S6F15 W
          <U2 1337 > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 6
    _function = 15

    _dataFormat = CEID

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS06F16(SecsStreamFunction):
    """
    event report data.

    **Data Items**

    - :class:`DATAID <secsgem.secs.dataitems.DATAID>`
    - :class:`CEID <secsgem.secs.dataitems.CEID>`
    - :class:`RPTID <secsgem.secs.dataitems.RPTID>`
    - :class:`V <secsgem.secs.dataitems.V>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F16
        {
            DATAID: U1/U2/U4/U8/I1/I2/I4/I8/A
            CEID: U1/U2/U4/U8/I1/I2/I4/I8/A
            RPT: [
                {
                    RPTID: U1/U2/U4/U8/I1/I2/I4/I8/A
                    V: [
                        DATA: L/BOOLEAN/U1/U2/U4/U8/I1/I2/I4/I8/F4/F8/A/B
                        ...
                    ]
                }
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F16({"DATAID": 1, "CEID": 1337, "RPT": [{"RPTID": 1000, "V": \
["VAR", secsgem.SecsVarU4(100)]}]})
        S6F16
          <L [3]
            <U1 1 >
            <U2 1337 >
            <L [1]
              <L [2]
                <U2 1000 >
                <L [2]
                  <A "VAR">
                  <U4 100 >
                >
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 6
    _function = 16

    _dataFormat = [
        DATAID,
        CEID,
        [
            [
                "RPT",   # name of the list
                RPTID,
                [V]
            ]
        ]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS06F19(SecsStreamFunction):
    """
    individual report request.

    **Data Items**

    - :class:`RPTID <secsgem.secs.dataitems.RPTID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F19
        RPTID: U1/U2/U4/U8/I1/I2/I4/I8/A

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F19(secsgem.SecsVarU4(1337))
        S6F19 W
          <U4 1337 > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 6
    _function = 19

    _dataFormat = RPTID

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS06F20(SecsStreamFunction):
    """
    individual report data.

    **Data Items**

    - :class:`V <secsgem.secs.dataitems.V>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F20
        [
            V: L/BOOLEAN/U1/U2/U4/U8/I1/I2/I4/I8/F4/F8/A/B
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F20(["ASD", 1337])
        S6F20
          <L [2]
            <A "ASD">
            <U2 1337 >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 6
    _function = 20

    _dataFormat = [V]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS06F21(SecsStreamFunction):
    """
    annotated individual report request.

    **Data Items**

    - :class:`RPTID <secsgem.secs.dataitems.RPTID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F21
        RPTID: U1/U2/U4/U8/I1/I2/I4/I8/A

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F21(secsgem.SecsVarU4(1337))
        S6F21 W
          <U4 1337 > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 6
    _function = 21

    _dataFormat = RPTID

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS06F22(SecsStreamFunction):
    """
    annotated individual report data.

    **Data Items**

    - :class:`VID <secsgem.secs.dataitems.VID>`
    - :class:`V <secsgem.secs.dataitems.V>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS06F22
        [
            {
                VID: U1/U2/U4/U8/I1/I2/I4/I8/A
                V: L/BOOLEAN/U1/U2/U4/U8/I1/I2/I4/I8/F4/F8/A/B
            }
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS06F22([{"VID": "VID1", "V": "ASD"}, {"VID": 2, "V": 1337}])
        S6F22
          <L [2]
            <L [2]
              <A "VID1">
              <A "ASD">
            >
            <L [2]
              <U1 2 >
              <U2 1337 >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: list
    """

    _stream = 6
    _function = 22

    _dataFormat = [
        [
            VID,
            V
        ]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS07F00(SecsStreamFunction):
    """
    abort transaction stream 7.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS07F00
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS07F00()
        S7F0 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 7
    _function = 0

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS07F01(SecsStreamFunction):
    """
    process program load - inquire.

    **Data Items**

    - :class:`PPID <secsgem.secs.dataitems.PPID>`
    - :class:`LENGTH <secsgem.secs.dataitems.LENGTH>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS07F01
        {
            PPID: A/B[120]
            LENGTH: U1/U2/U4/U8/I1/I2/I4/I8
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS07F01({"PPID": "program", "LENGTH": 4})
        S7F1 W
          <L [2]
            <A "program">
            <U1 4 >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 7
    _function = 1

    _dataFormat = [
        PPID,
        LENGTH
    ]

    _toHost = True
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS07F02(SecsStreamFunction):
    """
    process program load - grant.

    **Data Items**

    - :class:`PPGNT <secsgem.secs.dataitems.PPGNT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS07F02
        PPGNT: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS07F02(secsgem.PPGNT.OK)
        S7F2
          <B 0x0> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 7
    _function = 2

    _dataFormat = PPGNT

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS07F03(SecsStreamFunction):
    """
    process program - send.

    **Data Items**

    - :class:`PPID <secsgem.secs.dataitems.PPID>`
    - :class:`PPBODY <secsgem.secs.dataitems.PPBODY>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS07F03
        {
            PPID: A/B[120]
            PPBODY: U1/U2/U4/U8/I1/I2/I4/I8/A/B
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS07F03({"PPID": "program", "PPBODY": secsgem.SecsVarBinary("data")})
        S7F3 W
          <L [2]
            <A "program">
            <B 0x64 0x61 0x74 0x61>
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 7
    _function = 3

    _dataFormat = [
        PPID,
        PPBODY
    ]

    _toHost = True
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = True


class SecsS07F04(SecsStreamFunction):
    """
    process program - acknowledge.

    **Data Items**

    - :class:`ACKC7 <secsgem.secs.dataitems.ACKC7>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS07F04
        ACKC7: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS07F04(secsgem.ACKC7.MATRIX_OVERFLOW)
        S7F4
          <B 0x3> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 7
    _function = 4

    _dataFormat = ACKC7

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS07F05(SecsStreamFunction):
    """
    process program - request.

    **Data Items**

    - :class:`PPID <secsgem.secs.dataitems.PPID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS07F05
        PPID: A/B[120]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS07F05("program")
        S7F5 W
          <A "program"> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 7
    _function = 5

    _dataFormat = PPID

    _toHost = True
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS07F06(SecsStreamFunction):
    """
    process program - data.

    **Data Items**

    - :class:`PPID <secsgem.secs.dataitems.PPID>`
    - :class:`PPBODY <secsgem.secs.dataitems.PPBODY>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS07F06
        {
            PPID: A/B[120]
            PPBODY: U1/U2/U4/U8/I1/I2/I4/I8/A/B
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS07F06({"PPID": "program", "PPBODY": secsgem.SecsVarBinary("data")})
        S7F6
          <L [2]
            <A "program">
            <B 0x64 0x61 0x74 0x61>
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 7
    _function = 6

    _dataFormat = [
        PPID,
        PPBODY
    ]

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS07F17(SecsStreamFunction):
    """
    delete process program - send.

    **Data Items**

    - :class:`PPID <secsgem.secs.dataitems.PPID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS07F17
        [
            PPID: A/B[120]
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS07F17(["program1", "program2"])
        S7F17 W
          <L [2]
            <A "program1">
            <A "program2">
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 7
    _function = 17

    _dataFormat = [PPID]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS07F18(SecsStreamFunction):
    """
    delete process program - acknowledge.

    **Data Items**

    - :class:`ACKC7 <secsgem.secs.dataitems.ACKC7>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS07F18
        ACKC7: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS07F18(secsgem.ACKC7.MODE_UNSUPPORTED)
        S7F18
          <B 0x5> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 7
    _function = 18

    _dataFormat = ACKC7

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS07F19(SecsStreamFunction):
    """
    current equipment process program - request.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS07F19
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS07F19()
        S7F19 W .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 7
    _function = 19

    _dataFormat = None

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS07F20(SecsStreamFunction):
    """
    current equipment process program - data.

    **Data Items**

    - :class:`PPID <secsgem.secs.dataitems.PPID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS07F20
        [
            PPID: A/B[120]
            ...
        ]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS07F20(["program1", "program2"])
        S7F20
          <L [2]
            <A "program1">
            <A "program2">
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 7
    _function = 20

    _dataFormat = [PPID]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS09F00(SecsStreamFunction):
    """
    abort transaction stream 9.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS09F00
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS09F00()
        S9F0 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 9
    _function = 0

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS09F01(SecsStreamFunction):
    """
    unrecognized device id.

    **Data Items**

    - :class:`MHEAD <secsgem.secs.dataitems.MHEAD>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS09F01
        MHEAD: B[10]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS09F01("HEADERDATA")
        S9F1
          <B 0x48 0x45 0x41 0x44 0x45 0x52 0x44 0x41 0x54 0x41> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 9
    _function = 1

    _dataFormat = MHEAD

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS09F03(SecsStreamFunction):
    """
    unrecognized stream type.

    **Data Items**

    - :class:`MHEAD <secsgem.secs.dataitems.MHEAD>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS09F03
        MHEAD: B[10]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS09F03("HEADERDATA")
        S9F3
          <B 0x48 0x45 0x41 0x44 0x45 0x52 0x44 0x41 0x54 0x41> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 9
    _function = 3

    _dataFormat = MHEAD

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS09F05(SecsStreamFunction):
    """
    unrecognized function type.

    **Data Items**

    - :class:`MHEAD <secsgem.secs.dataitems.MHEAD>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS09F05
        MHEAD: B[10]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS09F05("HEADERDATA")
        S9F5
          <B 0x48 0x45 0x41 0x44 0x45 0x52 0x44 0x41 0x54 0x41> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 9
    _function = 5

    _dataFormat = MHEAD

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS09F07(SecsStreamFunction):
    """
    illegal data.

    **Data Items**

    - :class:`MHEAD <secsgem.secs.dataitems.MHEAD>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS09F07
        MHEAD: B[10]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS09F07("HEADERDATA")
        S9F7
          <B 0x48 0x45 0x41 0x44 0x45 0x52 0x44 0x41 0x54 0x41> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 9
    _function = 7

    _dataFormat = MHEAD

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS09F09(SecsStreamFunction):
    """
    transaction timer timeout.

    **Data Items**

    - :class:`SHEAD <secsgem.secs.dataitems.SHEAD>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS09F09
        SHEAD: B[10]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS09F09("HEADERDATA")
        S9F9
          <B 0x48 0x45 0x41 0x44 0x45 0x52 0x44 0x41 0x54 0x41> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 9
    _function = 9

    _dataFormat = SHEAD

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS09F11(SecsStreamFunction):
    """
    data too long.

    **Data Items**

    - :class:`MHEAD <secsgem.secs.dataitems.MHEAD>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS09F11
        MHEAD: B[10]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS09F11("HEADERDATA")
        S9F11
          <B 0x48 0x45 0x41 0x44 0x45 0x52 0x44 0x41 0x54 0x41> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 9
    _function = 11

    _dataFormat = MHEAD

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS09F13(SecsStreamFunction):
    """
    conversation timeout.

    **Data Items**

    - :class:`MEXP <secsgem.secs.dataitems.MEXP>`
    - :class:`EDID <secsgem.secs.dataitems.EDID>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS09F13
        {
            MEXP: A[6]
            EDID: U1/U2/U4/U8/I1/I2/I4/I8/A/B
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS09F13({"MEXP": "S01E01", "EDID": "data"})
        S9F13
          <L [2]
            <A "S01E01">
            <A "data">
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 9
    _function = 13

    _dataFormat = [
        MEXP,
        EDID
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS10F00(SecsStreamFunction):
    """
    abort transaction stream 10.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS10F00
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS10F00()
        S10F0 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 10
    _function = 0

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS10F01(SecsStreamFunction):
    """
    terminal - request.

    **Data Items**

    - :class:`TID <secsgem.secs.dataitems.TID>`
    - :class:`TEXT <secsgem.secs.dataitems.TEXT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS10F01
        {
            TID: B[1]
            TEXT: U1/U2/U4/U8/I1/I2/I4/I8/A/B
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS10F01({"TID": 0, "TEXT": "hello?"})
        S10F1
          <L [2]
            <B 0x0>
            <A "hello?">
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 10
    _function = 1

    _dataFormat = [
        TID,
        TEXT
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS10F02(SecsStreamFunction):
    """
    terminal - acknowledge.

    **Data Items**

    - :class:`ACKC10 <secsgem.secs.dataitems.ACKC10>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS10F02
        ACKC10: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS10F02(secsgem.ACKC10.ACCEPTED)
        S10F2
          <B 0x0> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 10
    _function = 2

    _dataFormat = ACKC10

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS10F03(SecsStreamFunction):
    """
    terminal single - display.

    **Data Items**

    - :class:`TID <secsgem.secs.dataitems.TID>`
    - :class:`TEXT <secsgem.secs.dataitems.TEXT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS10F03
        {
            TID: B[1]
            TEXT: U1/U2/U4/U8/I1/I2/I4/I8/A/B
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS10F03({"TID": 0, "TEXT": "hello!"})
        S10F3
          <L [2]
            <B 0x0>
            <A "hello!">
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 10
    _function = 3

    _dataFormat = [
        TID,
        TEXT
    ]

    _toHost = False
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS10F04(SecsStreamFunction):
    """
    terminal single - acknowledge.

    **Data Items**

    - :class:`ACKC10 <secsgem.secs.dataitems.ACKC10>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS10F04
        ACKC10: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS10F04(secsgem.ACKC10.TERMINAL_NOT_AVAILABLE)
        S10F4
          <B 0x2> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 10
    _function = 4

    _dataFormat = ACKC10

    _toHost = True
    _toEquipment = False

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS12F00(SecsStreamFunction):
    """
    abort transaction stream 12.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F00
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F00()
        S12F0 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 12
    _function = 0

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS12F01(SecsStreamFunction):
    """
    map setup data - send.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`
    - :class:`FNLOC <secsgem.secs.dataitems.FNLOC>`
    - :class:`FFROT <secsgem.secs.dataitems.FFROT>`
    - :class:`ORLOC <secsgem.secs.dataitems.ORLOC>`
    - :class:`RPSEL <secsgem.secs.dataitems.RPSEL>`
    - :class:`REFP <secsgem.secs.dataitems.REFP>`
    - :class:`DUTMS <secsgem.secs.dataitems.DUTMS>`
    - :class:`XDIES <secsgem.secs.dataitems.XDIES>`
    - :class:`YDIES <secsgem.secs.dataitems.YDIES>`
    - :class:`ROWCT <secsgem.secs.dataitems.ROWCT>`
    - :class:`COLCT <secsgem.secs.dataitems.COLCT>`
    - :class:`NULBC <secsgem.secs.dataitems.NULBC>`
    - :class:`PRDCT <secsgem.secs.dataitems.PRDCT>`
    - :class:`PRAXI <secsgem.secs.dataitems.PRAXI>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F01
        {
            MID: A/B[80]
            IDTYP: B[1]
            FNLOC: U2
            FFROT: U2
            ORLOC: B
            RPSEL: U1
            REFP: [
                DATA: I1/I2/I4/I8
                ...
            ]
            DUTMS: A
            XDIES: U1/U2/U4/U8/F4/F8
            YDIES: U1/U2/U4/U8/F4/F8
            ROWCT: U1/U2/U4/U8
            COLCT: U1/U2/U4/U8
            NULBC: U1/A
            PRDCT: U1/U2/U4/U8
            PRAXI: B[1]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F01({"MID": "materialID", \
                "IDTYP": secsgem.IDTYP.WAFER, \
                "FNLOC": 0, \
                "FFROT": 0, \
                "ORLOC": secsgem.ORLOC.UPPER_LEFT, \
                "RPSEL": 0, \
                "REFP": [[1,2], [2,3]], \
                "DUTMS": "unit", \
                "XDIES": 100, \
                "YDIES": 100, \
                "ROWCT": 10, \
                "COLCT": 10, \
                "NULBC": "{x}", \
                "PRDCT": 100, \
                "PRAXI": secsgem.PRAXI.ROWS_TOP_INCR, \
                })
        S12F1 W
          <L [15]
            <A "materialID">
            <B 0x0>
            <U2 0 >
            <U2 0 >
            <B 0x2>
            <U1 0 >
            <L [2]
              <I1 1 2 >
              <I1 2 3 >
            >
            <A "unit">
            <U1 100 >
            <U1 100 >
            <U1 10 >
            <U1 10 >
            <A "{x}">
            <U1 100 >
            <B 0x0>
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 1

    _dataFormat = [
        MID,
        IDTYP,
        FNLOC,
        FFROT,
        ORLOC,
        RPSEL,
        [REFP],
        DUTMS,
        XDIES,
        YDIES,
        ROWCT,
        COLCT,
        NULBC,
        PRDCT,
        PRAXI
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS12F02(SecsStreamFunction):
    """
    map setup data - acknowledge.

    **Data Items**

    - :class:`SDACK <secsgem.secs.dataitems.SDACK>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F02
        SDACK: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F02(secsgem.SDACK.ACK)
        S12F2
          <B 0x0> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 12
    _function = 2

    _dataFormat = SDACK

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS12F03(SecsStreamFunction):
    """
    map setup data - request.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`
    - :class:`MAPFT <secsgem.secs.dataitems.MAPFT>`
    - :class:`FNLOC <secsgem.secs.dataitems.FNLOC>`
    - :class:`FFROT <secsgem.secs.dataitems.FFROT>`
    - :class:`ORLOC <secsgem.secs.dataitems.ORLOC>`
    - :class:`PRAXI <secsgem.secs.dataitems.PRAXI>`
    - :class:`BCEQU <secsgem.secs.dataitems.BCEQU>`
    - :class:`NULBC <secsgem.secs.dataitems.NULBC>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F03
        {
            MID: A/B[80]
            IDTYP: B[1]
            MAPFT: B[1]
            FNLOC: U2
            FFROT: U2
            ORLOC: B
            PRAXI: B[1]
            BCEQU: U1/A
            NULBC: U1/A
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F03({"MID": "materialID", \
                "IDTYP": secsgem.IDTYP.WAFER_CASSETTE, \
                "MAPFT": secsgem.MAPFT.ROW, \
                "FNLOC": 0, \
                "FFROT": 0, \
                "ORLOC": secsgem.ORLOC.LOWER_LEFT, \
                "PRAXI": secsgem.PRAXI.COLS_LEFT_INCR, \
                "BCEQU": [1, 3, 5, 7], \
                "NULBC": "{x}", \
                })
        S12F3 W
          <L [9]
            <A "materialID">
            <B 0x1>
            <B 0x0>
            <U2 0 >
            <U2 0 >
            <B 0x3>
            <B 0x4>
            <U1 1 3 5 7 >
            <A "{x}">
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 3

    _dataFormat = [
        MID,
        IDTYP,
        MAPFT,
        FNLOC,
        FFROT,
        ORLOC,
        PRAXI,
        BCEQU,
        NULBC
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS12F04(SecsStreamFunction):
    """
    map setup data.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`
    - :class:`FNLOC <secsgem.secs.dataitems.FNLOC>`
    - :class:`ORLOC <secsgem.secs.dataitems.ORLOC>`
    - :class:`RPSEL <secsgem.secs.dataitems.RPSEL>`
    - :class:`REFP <secsgem.secs.dataitems.REFP>`
    - :class:`DUTMS <secsgem.secs.dataitems.DUTMS>`
    - :class:`XDIES <secsgem.secs.dataitems.XDIES>`
    - :class:`YDIES <secsgem.secs.dataitems.YDIES>`
    - :class:`ROWCT <secsgem.secs.dataitems.ROWCT>`
    - :class:`COLCT <secsgem.secs.dataitems.COLCT>`
    - :class:`PRDCT <secsgem.secs.dataitems.PRDCT>`
    - :class:`BCEQU <secsgem.secs.dataitems.BCEQU>`
    - :class:`NULBC <secsgem.secs.dataitems.NULBC>`
    - :class:`MLCL <secsgem.secs.dataitems.MLCL>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F04
        {
            MID: A/B[80]
            IDTYP: B[1]
            FNLOC: U2
            ORLOC: B
            RPSEL: U1
            REFP: [
                DATA: I1/I2/I4/I8
                ...
            ]
            DUTMS: A
            XDIES: U1/U2/U4/U8/F4/F8
            YDIES: U1/U2/U4/U8/F4/F8
            ROWCT: U1/U2/U4/U8
            COLCT: U1/U2/U4/U8
            PRDCT: U1/U2/U4/U8
            BCEQU: U1/A
            NULBC: U1/A
            MLCL: U1/U2/U4/U8
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F04({"MID": "materialID", \
                "IDTYP": secsgem.IDTYP.FILM_FRAME, \
                "FNLOC": 0, \
                "ORLOC": secsgem.ORLOC.CENTER_DIE, \
                "RPSEL": 0, \
                "REFP": [[1,2], [2,3]], \
                "DUTMS": "unit", \
                "XDIES": 100, \
                "YDIES": 100, \
                "ROWCT": 10, \
                "COLCT": 10, \
                "PRDCT": 100, \
                "BCEQU": [1, 3, 5, 7], \
                "NULBC": "{x}", \
                "MLCL": 0, \
                })
        S12F4
          <L [15]
            <A "materialID">
            <B 0x2>
            <U2 0 >
            <B 0x0>
            <U1 0 >
            <L [2]
              <I1 1 2 >
              <I1 2 3 >
            >
            <A "unit">
            <U1 100 >
            <U1 100 >
            <U1 10 >
            <U1 10 >
            <U1 100 >
            <U1 1 3 5 7 >
            <A "{x}">
            <U1 0 >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 4

    _dataFormat = [
        MID,
        IDTYP,
        FNLOC,
        ORLOC,
        RPSEL,
        [REFP],
        DUTMS,
        XDIES,
        YDIES,
        ROWCT,
        COLCT,
        PRDCT,
        BCEQU,
        NULBC,
        MLCL
    ]

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS12F05(SecsStreamFunction):
    """
    map transmit inquire.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`
    - :class:`MAPFT <secsgem.secs.dataitems.MAPFT>`
    - :class:`MLCL <secsgem.secs.dataitems.MLCL>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F05
        {
            MID: A/B[80]
            IDTYP: B[1]
            MAPFT: B[1]
            MLCL: U1/U2/U4/U8
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F05({"MID": "materialID", "IDTYP": secsgem.IDTYP.WAFER, "MAPFT": secsgem.MAPFT.ARRAY, \
"MLCL": 0})
        S12F5 W
          <L [4]
            <A "materialID">
            <B 0x0>
            <B 0x1>
            <U1 0 >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 5

    _dataFormat = [
        MID,
        IDTYP,
        MAPFT,
        MLCL
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS12F06(SecsStreamFunction):
    """
    map transmit - grant.

    **Data Items**

    - :class:`GRNT1 <secsgem.secs.dataitems.GRNT1>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F06
        GRNT1: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F06(secsgem.GRNT1.MATERIALID_UNKNOWN)
        S12F6
          <B 0x5> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 12
    _function = 6

    _dataFormat = GRNT1

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS12F07(SecsStreamFunction):
    """
    map data type 1 - send.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`
    - :class:`RSINF <secsgem.secs.dataitems.RSINF>`
    - :class:`BINLT <secsgem.secs.dataitems.BINLT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F07
        {
            MID: A/B[80]
            IDTYP: B[1]
            DATA: [
                {
                    RSINF: I1/I2/I4/I8[3]
                    BINLT: U1/A
                }
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F07({ \
            "MID": "materialID", \
            "IDTYP": secsgem.IDTYP.WAFER, \
            "DATA": [ \
                {"RSINF": [1, 2, 3], "BINLT": [1, 2, 3, 4]}, \
                {"RSINF": [4, 5, 6], "BINLT": [5, 6, 7, 8]}]})
        S12F7 W
          <L [3]
            <A "materialID">
            <B 0x0>
            <L [2]
              <L [2]
                <I1 1 2 3 >
                <U1 1 2 3 4 >
              >
              <L [2]
                <I1 4 5 6 >
                <U1 5 6 7 8 >
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 7

    _dataFormat = [
        MID,
        IDTYP,
        [
            [
                RSINF,
                BINLT
            ]
        ]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = True


class SecsS12F08(SecsStreamFunction):
    """
    map data type 1 - acknowledge.

    **Data Items**

    - :class:`MDACK <secsgem.secs.dataitems.MDACK>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F08
        MDACK: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F08(secsgem.MDACK.ABORT_MAP)
        S12F8
          <B 0x3> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 12
    _function = 8

    _dataFormat = MDACK

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS12F09(SecsStreamFunction):
    """
    map data type 2 - send.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`
    - :class:`STRP <secsgem.secs.dataitems.STRP>`
    - :class:`BINLT <secsgem.secs.dataitems.BINLT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F09
        {
            MID: A/B[80]
            IDTYP: B[1]
            STRP: I1/I2/I4/I8[2]
            BINLT: U1/A
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F09({"MID": "materialID", "IDTYP": secsgem.IDTYP.WAFER, "STRP": [0, 1], \
"BINLT": [1, 2, 3, 4, 5, 6]})
        S12F9 W
          <L [4]
            <A "materialID">
            <B 0x0>
            <I1 0 1 >
            <U1 1 2 3 4 5 6 >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 9

    _dataFormat = [
        MID,
        IDTYP,
        STRP,
        BINLT
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = True


class SecsS12F10(SecsStreamFunction):
    """
    map data type 2 - acknowledge.

    **Data Items**

    - :class:`MDACK <secsgem.secs.dataitems.MDACK>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F10
        MDACK: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F10(secsgem.MDACK.ACK)
        S12F10
          <B 0x0> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 12
    _function = 10

    _dataFormat = MDACK

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS12F11(SecsStreamFunction):
    """
    map data type 3 - send.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`
    - :class:`XYPOS <secsgem.secs.dataitems.XYPOS>`
    - :class:`BINLT <secsgem.secs.dataitems.BINLT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F11
        {
            MID: A/B[80]
            IDTYP: B[1]
            DATA: [
                {
                    XYPOS: I1/I2/I4/I8[2]
                    BINLT: U1/A
                }
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F11({ \
            "MID": "materialID", \
            "IDTYP": secsgem.IDTYP.WAFER, \
            "DATA": [ \
                {"XYPOS": [1, 2], "BINLT": [1, 2, 3, 4]}, \
                {"XYPOS": [3, 4], "BINLT": [5, 6, 7, 8]}]})
        S12F11 W
          <L [3]
            <A "materialID">
            <B 0x0>
            <L [2]
              <L [2]
                <I1 1 2 >
                <U1 1 2 3 4 >
              >
              <L [2]
                <I1 3 4 >
                <U1 5 6 7 8 >
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 11

    _dataFormat = [
        MID,
        IDTYP,
        [
            [
                XYPOS,
                BINLT
            ]
        ]
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = True


class SecsS12F12(SecsStreamFunction):
    """
    map data type 3 - acknowledge.

    **Data Items**

    - :class:`MDACK <secsgem.secs.dataitems.MDACK>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F12
        MDACK: B[1]

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F12(secsgem.MDACK.FORMAT_ERROR)
        S12F12
          <B 0x1> .

    :param value: parameters for this function (see example)
    :type value: byte
    """

    _stream = 12
    _function = 12

    _dataFormat = MDACK

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS12F13(SecsStreamFunction):
    """
    map data type 1 - request.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F13
        {
            MID: A/B[80]
            IDTYP: B[1]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F13({"MID": "materialID", "IDTYP": secsgem.IDTYP.WAFER})
        S12F13 W
          <L [2]
            <A "materialID">
            <B 0x0>
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 13

    _dataFormat = [
        MID,
        IDTYP
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS12F14(SecsStreamFunction):
    """
    map data type 1.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`
    - :class:`RSINF <secsgem.secs.dataitems.RSINF>`
    - :class:`BINLT <secsgem.secs.dataitems.BINLT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F14
        {
            MID: A/B[80]
            IDTYP: B[1]
            DATA: [
                {
                    RSINF: I1/I2/I4/I8[3]
                    BINLT: U1/A
                }
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F14({ \
            "MID": "materialID", \
            "IDTYP": secsgem.IDTYP.WAFER, \
            "DATA": [ \
                {"RSINF": [1, 2, 3], "BINLT": [1, 2, 3, 4]}, \
                {"RSINF": [4, 5, 6], "BINLT": [5, 6, 7, 8]}]})
        S12F14
          <L [3]
            <A "materialID">
            <B 0x0>
            <L [2]
              <L [2]
                <I1 1 2 3 >
                <U1 1 2 3 4 >
              >
              <L [2]
                <I1 4 5 6 >
                <U1 5 6 7 8 >
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 14

    _dataFormat = [
        MID,
        IDTYP,
        [
            [
                RSINF,
                BINLT
            ]
        ]
    ]

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS12F15(SecsStreamFunction):
    """
    map data type 2 - request.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F15
        {
            MID: A/B[80]
            IDTYP: B[1]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F15({"MID": "materialID", "IDTYP": secsgem.IDTYP.WAFER})
        S12F15 W
          <L [2]
            <A "materialID">
            <B 0x0>
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 15

    _dataFormat = [
        MID,
        IDTYP
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS12F16(SecsStreamFunction):
    """
    map data type 2.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`
    - :class:`STRP <secsgem.secs.dataitems.STRP>`
    - :class:`BINLT <secsgem.secs.dataitems.BINLT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F16
        {
            MID: A/B[80]
            IDTYP: B[1]
            STRP: I1/I2/I4/I8[2]
            BINLT: U1/A
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F16({"MID": "materialID", "IDTYP": secsgem.IDTYP.WAFER, "STRP": [0, 1], \
"BINLT": [1, 2, 3, 4, 5, 6]})
        S12F16
          <L [4]
            <A "materialID">
            <B 0x0>
            <I1 0 1 >
            <U1 1 2 3 4 5 6 >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 16

    _dataFormat = [
        MID,
        IDTYP,
        STRP,
        BINLT
    ]

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS12F17(SecsStreamFunction):
    """
    map data type 3 - request.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`
    - :class:`SDBIN <secsgem.secs.dataitems.SDBIN>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F17
        {
            MID: A/B[80]
            IDTYP: B[1]
            SDBIN: B[1]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F17({"MID": "materialID", "IDTYP": secsgem.IDTYP.WAFER, "SDBIN": secsgem.SDBIN.DONT_SEND})
        S12F17 W
          <L [3]
            <A "materialID">
            <B 0x0>
            <B 0x1>
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 17

    _dataFormat = [
        MID,
        IDTYP,
        SDBIN
    ]

    _toHost = True
    _toEquipment = False

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS12F18(SecsStreamFunction):
    """
    map data type 3.

    **Data Items**

    - :class:`MID <secsgem.secs.dataitems.MID>`
    - :class:`IDTYP <secsgem.secs.dataitems.IDTYP>`
    - :class:`XYPOS <secsgem.secs.dataitems.XYPOS>`
    - :class:`BINLT <secsgem.secs.dataitems.BINLT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F18
        {
            MID: A/B[80]
            IDTYP: B[1]
            DATA: [
                {
                    XYPOS: I1/I2/I4/I8[2]
                    BINLT: U1/A
                }
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F18({ \
                "MID": "materialID", \
                "IDTYP": secsgem.IDTYP.WAFER, \
                "DATA": [ \
                    {"XYPOS": [1, 2], "BINLT": [1, 2, 3, 4]}, \
                    {"XYPOS": [3, 4], "BINLT": [5, 6, 7, 8]}]})
        S12F18
          <L [3]
            <A "materialID">
            <B 0x0>
            <L [2]
              <L [2]
                <I1 1 2 >
                <U1 1 2 3 4 >
              >
              <L [2]
                <I1 3 4 >
                <U1 5 6 7 8 >
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 18

    _dataFormat = [
        MID,
        IDTYP,
        [
            [
                XYPOS,
                BINLT
            ]
        ]
    ]

    _toHost = False
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS12F19(SecsStreamFunction):
    """
    map error report - send.

    **Data Items**

    - :class:`MAPER <secsgem.secs.dataitems.MAPER>`
    - :class:`DATLC <secsgem.secs.dataitems.DATLC>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS12F19
        {
            MAPER: B[1]
            DATLC: U1
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS12F19({"MAPER": secsgem.MAPER.INVALID_DATA, "DATLC": 0})
        S12F19
          <L [2]
            <B 0x1>
            <U1 0 >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 12
    _function = 19

    _dataFormat = [
        MAPER,
        DATLC
    ]

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS14F00(SecsStreamFunction):
    """
    abort transaction stream 14.

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS14F00
        Header only

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS14F00()
        S14F0 .

    :param value: function has no parameters
    :type value: None
    """

    _stream = 14
    _function = 0

    _dataFormat = None

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = False


class SecsS14F01(SecsStreamFunction):
    """
    GetAttr request.

    **Data Items**

    - :class:`OBJSPEC <secsgem.secs.dataitems.OBJSPEC>`
    - :class:`OBJTYPE <secsgem.secs.dataitems.OBJTYPE>`
    - :class:`OBJID <secsgem.secs.dataitems.OBJID>`
    - :class:`ATTRID <secsgem.secs.dataitems.ATTRID>`
    - :class:`ATTRDATA <secsgem.secs.dataitems.ATTRDATA>`
    - :class:`ATTRRELN <secsgem.secs.dataitems.ATTRRELN>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS14F01
        {
            OBJSPEC: A
            OBJTYPE: U1/U2/U4/U8/A
            OBJID: [
                DATA: U1/U2/U4/U8/A
                ...
            ]
            FILTER: [
                {
                    ATTRID: U1/U2/U4/U8/A
                    ATTRDATA: L/BOOLEAN/U1/U2/U4/U8/I1/I2/I4/I8/F4/F8/A/B
                    ATTRRELN: U1
                }
                ...
            ]
            ATTRID: [
                DATA: U1/U2/U4/U8/A
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS14F01({ \
            "OBJSPEC": '', \
            "OBJTYPE": 'StripMap', \
            "OBJID": ['MAP001'], \
            "FILTER": [], \
            "ATTRID": ['OriginLocation', 'Rows', 'Columns', 'CellStatus', 'LotID']})
        S14F1 W
          <L [5]
            <A>
            <A "StripMap">
            <L [1]
              <A "MAP001">
            >
            <L>
            <L [5]
              <A "OriginLocation">
              <A "Rows">
              <A "Columns">
              <A "CellStatus">
              <A "LotID">
            >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 14
    _function = 1

    _dataFormat = [
        OBJSPEC,
        OBJTYPE,
        [OBJID],
        [
            [
                "FILTER",   # name of the list
                ATTRID,
                ATTRDATA,
                ATTRRELN
            ]
        ],
        [ATTRID]
    ]

    _toHost = True
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False

    RELATION = {
        "EQUAL": 0,
        "NOTEQUAL": 1,
        "LESS": 2,
        "LESSEQUAL": 3,
        "GREATER": 4,
        "GREATEREQUAL": 5,
        "PRESENT": 6,
        "NOTPRESENT": 7,
    }


class SecsS14F02(SecsStreamFunction):
    """
    GetAttr data.

    **Data Items**

    - :class:`OBJID <secsgem.secs.dataitems.OBJID>`
    - :class:`ATTRID <secsgem.secs.dataitems.ATTRID>`
    - :class:`ATTRDATA <secsgem.secs.dataitems.ATTRDATA>`
    - :class:`OBJACK <secsgem.secs.dataitems.OBJACK>`
    - :class:`ERRCODE <secsgem.secs.dataitems.ERRCODE>`
    - :class:`ERRTEXT <secsgem.secs.dataitems.ERRTEXT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS14F02
        {
            DATA: [
                {
                    OBJID: U1/U2/U4/U8/A
                    ATTRIBS: [
                        {
                            ATTRID: U1/U2/U4/U8/A
                            ATTRDATA: L/BOOLEAN/U1/U2/U4/U8/I1/I2/I4/I8/F4/F8/A/B
                        }
                        ...
                    ]
                }
                ...
            ]
            ERRORS: {
                OBJACK: U1[1]
                ERROR: [
                    {
                        ERRCODE: I1/I2/I4/I8
                        ERRTEXT: A[120]
                    }
                    ...
                ]
            }
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS14F02({ \
            "DATA": [{ \
                "OBJID": "MAP001", \
                "ATTRIBS": [ \
                    {"ATTRID": "OriginLocation", "ATTRDATA": "0"}, \
                    {"ATTRID": "Rows", "ATTRDATA": 4}, \
                    {"ATTRID": "Columns", "ATTRDATA": 4}, \
                    {"ATTRID": "CellStatus", "ATTRDATA": 6}, \
                    {"ATTRID": "LotID", "ATTRDATA":"LOT001"}]}], \
                "ERRORS": {"OBJACK": 0}})
        S14F2
          <L [2]
            <L [1]
              <L [2]
                <A "MAP001">
                <L [5]
                  <L [2]
                    <A "OriginLocation">
                    <A "0">
                  >
                  <L [2]
                    <A "Rows">
                    <U1 4 >
                  >
                  <L [2]
                    <A "Columns">
                    <U1 4 >
                  >
                  <L [2]
                    <A "CellStatus">
                    <U1 6 >
                  >
                  <L [2]
                    <A "LotID">
                    <A "LOT001">
                  >
                >
              >
            >
            <L [2]
              <U1 0 >
              <L>
            >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 14
    _function = 2

    _dataFormat = [
        [
            [
                OBJID,
                [
                    [
                        "ATTRIBS",   # name of the list
                        ATTRID,
                        ATTRDATA
                    ]
                ]
            ]
        ],
        [
            "ERRORS",   # name of the list
            OBJACK,
            [
                [
                    "ERROR",   # name of the list
                    ERRCODE,
                    ERRTEXT
                ]
            ]
        ]
    ]

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


class SecsS14F03(SecsStreamFunction):
    """
    SetAttr request.

    **Data Items**

    - :class:`OBJSPEC <secsgem.secs.dataitems.OBJSPEC>`
    - :class:`OBJTYPE <secsgem.secs.dataitems.OBJTYPE>`
    - :class:`OBJID <secsgem.secs.dataitems.OBJID>`
    - :class:`ATTRID <secsgem.secs.dataitems.ATTRID>`
    - :class:`ATTRDATA <secsgem.secs.dataitems.ATTRDATA>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS14F03
        {
            OBJSPEC: A
            OBJTYPE: U1/U2/U4/U8/A
            OBJID: [
                DATA: U1/U2/U4/U8/A
                ...
            ]
            ATTRIBS: [
                {
                    ATTRID: U1/U2/U4/U8/A
                    ATTRDATA: L/BOOLEAN/U1/U2/U4/U8/I1/I2/I4/I8/F4/F8/A/B
                }
                ...
            ]
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS14F03({"OBJSPEC": '', "OBJTYPE": 'StripMap', "OBJID": ['MAP001'], \
"ATTRIBS": [ {"ATTRID": "CellStatus", "ATTRDATA": "3"} ] })
        S14F3 W
          <L [4]
            <A>
            <A "StripMap">
            <L [1]
              <A "MAP001">
            >
            <L [1]
              <L [2]
                <A "CellStatus">
                <A "3">
              >
            >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 14
    _function = 3

    _dataFormat = [
        OBJSPEC,
        OBJTYPE,
        [OBJID],
        [
            [
                "ATTRIBS",   # name of the list
                ATTRID,
                ATTRDATA
            ]
        ]
    ]

    _toHost = True
    _toEquipment = True

    _hasReply = True
    _isReplyRequired = True

    _isMultiBlock = False


class SecsS14F04(SecsStreamFunction):
    """
    SetAttr data.

    **Data Items**

    - :class:`OBJID <secsgem.secs.dataitems.OBJID>`
    - :class:`ATTRID <secsgem.secs.dataitems.ATTRID>`
    - :class:`ATTRDATA <secsgem.secs.dataitems.ATTRDATA>`
    - :class:`OBJACK <secsgem.secs.dataitems.OBJACK>`
    - :class:`ERRCODE <secsgem.secs.dataitems.ERRCODE>`
    - :class:`ERRTEXT <secsgem.secs.dataitems.ERRTEXT>`

    **Structure**::

        >>> import secsgem
        >>> secsgem.SecsS14F04
        {
            DATA: [
                {
                    OBJID: U1/U2/U4/U8/A
                    ATTRIBS: [
                        {
                            ATTRID: U1/U2/U4/U8/A
                            ATTRDATA: L/BOOLEAN/U1/U2/U4/U8/I1/I2/I4/I8/F4/F8/A/B
                        }
                        ...
                    ]
                }
                ...
            ]
            ERRORS: {
                OBJACK: U1[1]
                ERROR: [
                    {
                        ERRCODE: I1/I2/I4/I8
                        ERRTEXT: A[120]
                    }
                    ...
                ]
            }
        }

    **Example**::

        >>> import secsgem
        >>> secsgem.SecsS14F04({ \
            "DATA": [{ \
                "OBJID": "MAP001", \
                "ATTRIBS": [ \
                    {"ATTRID": "OriginLocation", "ATTRDATA": "0"}, \
                    {"ATTRID": "Rows", "ATTRDATA": 4}, \
                    {"ATTRID": "Columns", "ATTRDATA": 4}, \
                    {"ATTRID": "CellStatus", "ATTRDATA": 6}, \
                    {"ATTRID": "LotID", "ATTRDATA":"LOT001"}]}], \
                "ERRORS": {"OBJACK": 0}})
        S14F4
          <L [2]
            <L [1]
              <L [2]
                <A "MAP001">
                <L [5]
                  <L [2]
                    <A "OriginLocation">
                    <A "0">
                  >
                  <L [2]
                    <A "Rows">
                    <U1 4 >
                  >
                  <L [2]
                    <A "Columns">
                    <U1 4 >
                  >
                  <L [2]
                    <A "CellStatus">
                    <U1 6 >
                  >
                  <L [2]
                    <A "LotID">
                    <A "LOT001">
                  >
                >
              >
            >
            <L [2]
              <U1 0 >
              <L>
            >
          > .

    :param value: parameters for this function (see example)
    :type value: dict
    """

    _stream = 14
    _function = 4

    _dataFormat = [
        [
            [
                OBJID,
                [
                    [
                        "ATTRIBS",   # name of the list
                        ATTRID,
                        ATTRDATA
                    ]
                ]
            ]
        ],
        [
            "ERRORS",   # name of the list
            OBJACK,
            [
                [
                    "ERROR",   # name of the list
                    ERRCODE,
                    ERRTEXT
                ]
            ]
        ]
    ]

    _toHost = True
    _toEquipment = True

    _hasReply = False
    _isReplyRequired = False

    _isMultiBlock = True


secsStreamsFunctions = {
    0: {
        0: SecsS00F00,
    },
    1: {
        0: SecsS01F00,
        1: SecsS01F01,
        2: SecsS01F02,
        3: SecsS01F03,
        4: SecsS01F04,
        11: SecsS01F11,
        12: SecsS01F12,
        13: SecsS01F13,
        14: SecsS01F14,
        15: SecsS01F15,
        16: SecsS01F16,
        17: SecsS01F17,
        18: SecsS01F18,
    },
    2: {
        0: SecsS02F00,
        13: SecsS02F13,
        14: SecsS02F14,
        15: SecsS02F15,
        16: SecsS02F16,
        17: SecsS02F17,
        18: SecsS02F18,
        29: SecsS02F29,
        30: SecsS02F30,
        33: SecsS02F33,
        34: SecsS02F34,
        35: SecsS02F35,
        36: SecsS02F36,
        37: SecsS02F37,
        38: SecsS02F38,
        41: SecsS02F41,
        42: SecsS02F42,
    },
    5: {
        0: SecsS05F00,
        1: SecsS05F01,
        2: SecsS05F02,
        3: SecsS05F03,
        4: SecsS05F04,
        5: SecsS05F05,
        6: SecsS05F06,
        7: SecsS05F07,
        8: SecsS05F08,
        9: SecsS05F09,
        10: SecsS05F10,
        11: SecsS05F11,
        12: SecsS05F12,
        13: SecsS05F13,
        14: SecsS05F14,
        15: SecsS05F15,
        16: SecsS05F16,
        17: SecsS05F17,
        18: SecsS05F18,
    },
    6: {
        0: SecsS06F00,
        5: SecsS06F05,
        6: SecsS06F06,
        7: SecsS06F07,
        8: SecsS06F08,
        11: SecsS06F11,
        12: SecsS06F12,
        15: SecsS06F15,
        16: SecsS06F16,
        19: SecsS06F19,
        20: SecsS06F20,
        21: SecsS06F21,
        22: SecsS06F22,
    },
    7: {
        1: SecsS07F01,
        2: SecsS07F02,
        3: SecsS07F03,
        4: SecsS07F04,
        5: SecsS07F05,
        6: SecsS07F06,
        17: SecsS07F17,
        18: SecsS07F18,
        19: SecsS07F19,
        20: SecsS07F20,
    },
    9: {
        0: SecsS09F00,
        1: SecsS09F01,
        3: SecsS09F03,
        5: SecsS09F05,
        7: SecsS09F07,
        9: SecsS09F09,
        11: SecsS09F11,
        13: SecsS09F13,
    },
    10: {
        0: SecsS10F00,
        1: SecsS10F01,
        2: SecsS10F02,
        3: SecsS10F03,
        4: SecsS10F04,
    },
    12: {
        0: SecsS12F00,
        1: SecsS12F01,
        2: SecsS12F02,
        3: SecsS12F03,
        4: SecsS12F04,
        5: SecsS12F05,
        6: SecsS12F06,
        7: SecsS12F07,
        8: SecsS12F08,
        9: SecsS12F09,
        10: SecsS12F10,
        11: SecsS12F11,
        12: SecsS12F12,
        13: SecsS12F13,
        14: SecsS12F14,
        15: SecsS12F15,
        16: SecsS12F16,
        17: SecsS12F17,
        18: SecsS12F18,
        19: SecsS12F19,
    },
    14: {
        0: SecsS14F00,
        1: SecsS14F01,
        2: SecsS14F02,
        3: SecsS14F03,
        4: SecsS14F04,
    },
}


hsmsSTypes = {
    1: "Select.req",
    2: "Select.rsp",
    3: "Deselect.req",
    4: "Deselect.rsp",
    5: "Linktest.req",
    6: "Linktest.rsp",
    7: "Reject.req",
    9: "Separate.req"
}
"""Names for hsms header SType."""


def is_errorcode_ewouldblock(errorcode):
    """
    Check if the errorcode is a would-block error.

    :param errorcode: Code of the error
    :return: True if blocking error code
    """
    if errorcode in (errno.EAGAIN, errno.EWOULDBLOCK):
        return True

    return False


class HsmsConnection:  # pragma: no cover
    """Connection class used for active and passive hsms connections."""

    selectTimeout = 0.5
    """ Timeout for select calls ."""

    sendBlockSize = 1024 * 1024
    """ Block size for outbound data ."""

    T3 = 45.0
    """ Reply Timeout ."""

    T5 = 10.0
    """ Connect Separation Time ."""

    T6 = 5.0
    """ Control Transaction Timeout ."""

    def __init__(self, active, address, port, session_id=0, delegate=None):
        """
        Initialize a hsms connection.

        :param active: Is the connection active (*True*) or passive (*False*)
        :type active: boolean
        :param address: IP address of remote host
        :type address: string
        :param port: TCP port of remote host
        :type port: integer
        :param session_id: session / device ID to use for connection
        :type session_id: integer
        :param delegate: target for messages
        :type delegate: inherited from :class:`secsgem.hsms.handler.HsmsHandler`
        """

        # set parameters
        self.active = active
        self.remoteAddress = address
        self.remotePort = port
        self.sessionID = session_id
        self.delegate = delegate

        # connection socket
        self.sock = None

        # buffer for received data
        self.receiveBuffer = b""

        # receiving thread flags
        self.threadRunning = False
        self.stopThread = False

        # connected flag
        self.connected = False

        # flag set during disconnection
        self.disconnecting = False

    def _serialize_data(self):
        """
        Returns data for serialization.

        :returns: data to serialize for this object
        :rtype: dict
        """
        return {
            'active': self.active,
            'remoteAddress': self.remoteAddress,
            'remotePort': self.remotePort,
            'sessionID': self.sessionID,
            'connected': self.connected}

    def __str__(self):
        """Get the contents of this object as a string."""
        return "{} connection to {}:{} sessionID={}".format(("Active" if self.active else "Passive"),
                                                            self.remoteAddress, str(self.remotePort),
                                                            str(self.sessionID))

    def _start_receiver(self):
        """
        Start the thread for receiving and handling incoming messages.

        Will also do the initial Select and Linktest requests.

        .. warning:: Do not call this directly, will be called from HSMS client/server class.
        .. seealso:: :class:`secsgem.hsms.connections.HsmsActiveConnection`,
            :class:`secsgem.hsms.connections.HsmsPassiveConnection`,
            :class:`secsgem.hsms.connections.HsmsMultiPassiveConnection`
        """
        # mark connection as connected
        self.connected = True

        # start data receiving thread
        threading.Thread(target=self.__receiver_thread, args=(),
                         name="secsgem_hsmsConnection_receiver_{}:{}".format(self.remoteAddress,
                                                                             self.remotePort)).start()

        # wait until thread is running
        while not self.threadRunning:
            pass

        # send event
        if self.delegate and hasattr(self.delegate, 'on_connection_established') \
                and callable(getattr(self.delegate, 'on_connection_established')):
            try:
                self.delegate.on_connection_established(self)
            except Exception:
                if self.delegate != None:
                    self.delegate.secsgem_logging('ignoring exception for on_connection_established handler', 'trace')

    def _on_hsms_connection_close(self, data):
        pass

    def disconnect(self):
        """Close connection."""
        # return if thread isn't running
        if not self.threadRunning:
            return

        # set disconnecting flag to avoid another select
        self.disconnecting = True

        # set flag to stop the thread
        self.stopThread = True

        # wait until thread stopped
        while self.threadRunning:
            pass

        # clear disconnecting flag, no selects coming any more
        self.disconnecting = False

    def send_packet(self, packet):
        """
        Send the ASCII coded packet to the remote host.

        :param packet: encoded data to be transmitted
        :type packet: string / byte array
        """
        # encode the packet
        data = packet.encode()

        # split data into blocks
        blocks = [data[i: i + self.sendBlockSize] for i in range(0, len(data), self.sendBlockSize)]

        for block in blocks:
            retry = True

            # not sent yet, retry
            while retry:
                # wait until socket is writable
                while not select.select([], [self.sock], [], self.selectTimeout)[1]:
                    pass

                try:
                    # send packet
                    self.sock.send(block)

                    # retry will be cleared if send succeeded
                    retry = False
                except OSError as e:
                    if not is_errorcode_ewouldblock(e.errno):
                        # raise if not EWOULDBLOCK
                        return False
                    # it is EWOULDBLOCK, so retry sending

        return True

    def _process_receive_buffer(self):
        """
        Parse the receive buffer and dispatch callbacks.

        .. warning:: Do not call this directly, will be called from
        :func:`secsgem.hsmsConnections.hsmsConnection.__receiver_thread` method.
        """
        # check if enough data in input buffer
        if len(self.receiveBuffer) < 4:
            return False

        # unpack length from input buffer
        length = struct.unpack(">L", self.receiveBuffer[0:4])[0] + 4

        # check if enough data in input buffer
        if len(self.receiveBuffer) < length:
            return False

        # extract and remove packet from input buffer
        data = self.receiveBuffer[0:length]
        self.receiveBuffer = self.receiveBuffer[length:]

        # decode received packet
        response = HsmsPacket.decode(data)

        # redirect packet to hsms handler
        if self.delegate and hasattr(self.delegate, 'on_connection_packet_received') \
                and callable(getattr(self.delegate, 'on_connection_packet_received')):
            try:
                self.delegate.on_connection_packet_received(self, response)
            except Exception:
                if self.delegate != None:
                    self.delegate.secsgem_logging('ignoring exception for on_connection_packet_received handler', 'trace')

        # return True if more data is available
        if len(self.receiveBuffer) > 0:
            return True

        return False

    def __receiver_thread_read_data(self):
        # check if shutdown requested
        while not self.stopThread:
            # check if data available
            select_result = select.select([self.sock], [], [self.sock], self.selectTimeout)

            # check if disconnection was started
            if self.disconnecting:
                time.sleep(0.2)
                continue

            if select_result[0]:
                try:
                    # get data from socket
                    recv_data = self.sock.recv(1024)

                    # check if socket was closed
                    if len(recv_data) == 0:
                        self.connected = False
                        self.stopThread = True
                        continue

                    # add received data to input buffer
                    self.receiveBuffer += recv_data
                except OSError as e:
                    if not is_errorcode_ewouldblock(e.errno):
                        raise e

                # handle data in input buffer
                while self._process_receive_buffer():
                    pass

    def __receiver_thread(self):
        """
        Thread for receiving incoming data and adding it to the receive buffer.

        .. warning:: Do not call this directly, will be called from
        :func:`secsgem.hsmsConnections.hsmsConnection._startReceiver` method.
        """
        self.threadRunning = True

        try:
            self.__receiver_thread_read_data()
        except Exception:
            if self.delegate != None:
                self.delegate.secsgem_logging('__receiver_thread_read_data exception', 'trace')

        # notify listeners of disconnection
        if self.delegate and hasattr(self.delegate, 'on_connection_before_closed') \
                and callable(getattr(self.delegate, 'on_connection_before_closed')):
            try:
                self.delegate.on_connection_before_closed(self)
            except Exception:
                if self.delegate != None:
                    self.delegate.secsgem_logging('ignoring exception for on_connection_before_closed handler', 'trace')

        # close the socket
        self.sock.close()

        # notify listeners of disconnection
        if self.delegate and hasattr(self.delegate, 'on_connection_closed') \
                and callable(getattr(self.delegate, 'on_connection_closed')):
            try:
                self.delegate.on_connection_closed(self)
            except Exception:
                if self.delegate != None:
                    self.delegate.secsgem_logging('ignoring exception for on_connection_closed handler', 'trace')

        # reset all flags
        self.connected = False
        self.threadRunning = False
        self.stopThread = False

        # clear receive buffer
        self.receiveBuffer = b""

        # notify inherited classes of disconnection
        self._on_hsms_connection_close({'connection': self})


class HsmsPassiveConnection(HsmsConnection):  # pragma: no cover
    """
    Server class for single passive (incoming) connection.

    Creates a listening socket and waits for one incoming connection on this socket.
    After the connection is established the listening socket is closed.
    """

    def __init__(self, address, port=5000, session_id=0, delegate=None):
        """
        Initialize a passive hsms connection.

        :param address: IP address of target host
        :type address: string
        :param port: TCP port of target host
        :type port: integer
        :param session_id: session / device ID to use for connection
        :type session_id: integer
        :param delegate: target for messages
        :type delegate: object

        **Example**::

            # TODO: create example

        """
        # initialize super class
        HsmsConnection.__init__(self, True, address, port, session_id, delegate)

        # initially not enabled
        self.enabled = False

        # reconnect thread required for passive connection
        self.serverThread = None
        self.stopServerThread = False
        self.serverSock = None

    def _on_hsms_connection_close(self, data):
        """
        Signal from super that the connection was closed.

        This is required to initiate the reconnect if the connection is still enabled
        """
        if self.enabled:
            self.__start_server_thread()

    def enable(self):
        """
        Enable the connection.

        Starts the connection process to the passive remote.
        """
        # only start if not already enabled
        if not self.enabled:
            # mark connection as enabled
            self.enabled = True

            # start the connection thread
            self.__start_server_thread()

    def disable(self):
        """
        Disable the connection.

        Stops all connection attempts, and closes the connection
        """
        # only stop if enabled
        if self.enabled:
            # mark connection as disabled
            self.enabled = False

            # stop connection thread if it is running
            if self.serverThread and self.serverThread.is_alive():
                self.stopServerThread = True

                if self.serverSock:
                    self.serverSock.close()

                # wait for connection thread to stop
                while self.stopServerThread:
                    time.sleep(0.2)

            # disconnect super class
            self.disconnect()

    def __start_server_thread(self):
        self.serverThread = threading.Thread(target=self.__server_thread,
                                             name="secsgem_HsmsPassiveConnection_serverThread_{}"
                                             .format(self.remoteAddress))
        self.serverThread.start()

    def __server_thread(self):
        """
        Thread function to (re)connect active connection to remote host.

        .. warning:: Do not call this directly, for internal use only.
        """
        self.serverSock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

        if not is_windows():
            self.serverSock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

        self.serverSock.bind(('', self.remotePort))
        self.serverSock.listen(1)

        while not self.stopServerThread:
            try:
                select_result = select.select([self.serverSock], [], [], self.selectTimeout)
            except Exception:
                continue

            if not select_result[0]:
                # select timed out
                continue

            accept_result = self.serverSock.accept()
            if accept_result is None:
                continue

            (self.sock, (_, _)) = accept_result

            # setup socket
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)

            # make socket nonblocking
            self.sock.setblocking(0)

            # start the receiver thread
            self._start_receiver()

            self.serverSock.close()

            return

        self.stopServerThread = False


class HsmsMultiPassiveConnection(HsmsConnection):  # pragma: no cover
    """
    Connection class for single connection from :class:`secsgem.hsms.connections.HsmsMultiPassiveServer`.

    Handles connections incoming connection from :class:`secsgem.hsms.connections.HsmsMultiPassiveServer`
    """

    def __init__(self, address, port=5000, session_id=0, delegate=None):
        """
        Initialize a passive client connection.

        :param address: IP address of target host
        :type address: string
        :param port: TCP port of target host
        :type port: integer
        :param session_id: session / device ID to use for connection
        :type session_id: integer
        :param delegate: target for messages
        :type delegate: object

        **Example**::

            # TODO: create example

        """
        # initialize super class
        HsmsConnection.__init__(self, True, address, port, session_id, delegate)

        # initially not enabled
        self.enabled = False

    def on_connected(self, sock, address):
        """
        Connected callback for :class:`secsgem.hsms.connections.HsmsMultiPassiveServer`.

        :param sock: Socket for new connection
        :type sock: :class:`Socket`
        :param address: IP address of remote host
        :type address: string
        """
        del address  # unused parameter

        # setup socket
        self.sock = sock
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)

        # make socket nonblocking
        self.sock.setblocking(0)

        # start the receiver thread
        self._start_receiver()

    def enable(self):
        """
        Enable the connection.

        Starts the connection process to the passive remote.
        """
        self.enabled = True

    def disable(self):
        """
        Disable the connection.

        Stops all connection attempts, and closes the connection
        """
        self.enabled = False
        if self.connected:
            self.disconnect()


class HsmsMultiPassiveServer:  # pragma: no cover
    """
    Server class for multiple passive (incoming) connection.

    The server creates a listening socket and waits for incoming connections on this socket.
    """

    selectTimeout = 0.5
    """ Timeout for select calls ."""

    def __init__(self, port=5000):
        """
        Initialize a passive hsms server.

        :param port: TCP port to listen on
        :type port: integer

        **Example**::

            # TODO: create example

        """

        self.listenSock = None

        self.port = port

        self.threadRunning = False
        self.stopThread = False

        self.connections = {}

        self.listenThread = None

    def create_connection(self, address, port=5000, session_id=0, delegate=None):
        """
        Create and remember connection for the server.

        :param address: IP address of target host
        :type address: string
        :param port: TCP port of target host
        :type port: integer
        :param session_id: session / device ID to use for connection
        :type session_id: integer
        :param delegate: target for messages
        :type delegate: object
        """
        connection = HsmsMultiPassiveConnection(address, port, session_id, delegate)
        connection.handler = self

        self.connections[address] = connection

        return connection

    def start(self):
        """
        Starts the server and returns.

        It will launch a listener running in background to wait for incoming connections.
        """
        self.listenSock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

        if not is_windows():
            self.listenSock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

        self.listenSock.bind(('', self.port))
        self.listenSock.listen(1)
        self.listenSock.setblocking(0)

        self.listenThread = threading.Thread(target=self._listen_thread, args=(),
                                             name="secsgem_hsmsMultiPassiveServer_listenThread_{}".format(self.port))
        self.listenThread.start()

        if self.delegate != None:
            self.delegate.secsgem_logging('listening', 'trace')

    def stop(self, terminate_connections=True):
        """
        Stops the server. The background job waiting for incoming connections will be terminated.

        Optionally all connections received will be closed.

        :param terminate_connections: terminate all connection made by this server
        :type terminate_connections: boolean
        """
        self.stopThread = True

        if self.listenThread.is_alive():
            while self.threadRunning:
                pass

        self.listenSock.close()

        self.stopThread = False

        if terminate_connections:
            for address in self.connections:
                connection = self.connections[address]
                connection.disconnect()
        if self.delegate != None:
            self.delegate.secsgem_logging('server stopped', 'trace')

    def _initialize_connection_thread(self, accept_result):
        """
        Setup connection.

        .. warning:: Do not call this directly, used internally.
        """
        (sock, (source_ip, _)) = accept_result

        sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)

        new_connection = None

        # check if connection available with source ip
        if source_ip not in self.connections:
            named_connection_found = False

            # check all connections if connection with hostname can be resolved
            for connectionID in self.connections:
                connection = self.connections[connectionID]
                try:
                    if source_ip == socket.gethostbyname(connection.remoteAddress):
                        new_connection = connection
                        named_connection_found = True
                        break
                except socket.gaierror:
                    pass

            if not named_connection_found:
                sock.close()
                return
        else:
            new_connection = self.connections[source_ip]

        if not new_connection.enabled:
            sock.close()
            return

        new_connection.on_connected(sock, source_ip)

    def _listen_thread(self):
        """
        Thread listening for incoming connections.

        .. warning:: Do not call this directly, used internally.
        """
        self.threadRunning = True
        try:
            while not self.stopThread:
                # check for data in the input buffer
                select_result = select.select([self.listenSock], [], [self.listenSock], self.selectTimeout)

                if select_result[0]:
                    accept_result = None

                    try:
                        accept_result = self.listenSock.accept()
                    except OSError as e:
                        if not is_errorcode_ewouldblock(e.errno):
                            raise e

                    if accept_result is None:
                        continue

                    if self.stopThread:
                        continue

                    if self.delegate != None:
                        self.delegate.secsgem_logging("connection from {}:{}".format(accept_result[1][0], accept_result[1][1]), 'trace')

                    threading.Thread(target=self._initialize_connection_thread, args=(accept_result,),
                                     name="secsgem_hsmsMultiPassiveServer_InitializeConnectionThread_{}:{}"
                                     .format(accept_result[1][0], accept_result[1][1])).start()

        except Exception:
            if self.delegate != None:
                self.delegate.secsgem_logging('_listen_thread::exception', 'trace')

        self.threadRunning = False


class HsmsActiveConnection(HsmsConnection):  # pragma: no cover
    """Client class for single active (outgoing) connection."""

    def __init__(self, address, port=5000, session_id=0, delegate=None):
        """
        Initialize a active hsms connection.

        :param address: IP address of target host
        :type address: string
        :param port: TCP port of target host
        :type port: integer
        :param session_id: session / device ID to use for connection
        :type session_id: integer
        :param delegate: target for messages
        :type delegate: object

        **Example**::

            # TODO: create example

        """
        # initialize super class
        HsmsConnection.__init__(self, True, address, port, session_id, delegate)

        # initially not enabled
        self.enabled = False

        # reconnect thread required for active connection
        self.connectionThread = None
        self.stopConnectionThread = False

        # flag if this is the first connection since enable
        self.firstConnection = True

    def _on_hsms_connection_close(self, data):
        """
        Signal from super that the connection was closed.

        This is required to initiate the reconnect if the connection is still enabled
        """
        if self.enabled:
            self.__start_connect_thread()

    def enable(self):
        """
        Enable the connection.

        Starts the connection process to the passive remote.
        """
        # only start if not already enabled
        if not self.enabled:
            # reset first connection to eliminate reconnection timeout
            self.firstConnection = True

            # mark connection as enabled
            self.enabled = True

            # start the connection thread
            self.__start_connect_thread()

    def disable(self):
        """
        Disable the connection.

        Stops all connection attempts, and closes the connection
        """
        # only stop if enabled
        if self.enabled:
            # mark connection as disabled
            self.enabled = False

            # stop connection thread if it is running
            if self.connectionThread and self.connectionThread.is_alive():
                self.stopConnectionThread = True

            # wait for connection thread to stop
            while self.stopConnectionThread:
                time.sleep(0.2)

            # disconnect super class
            self.disconnect()

    def __idle(self, timeout):
        """
        Wait until timeout elapsed or connection thread is stopped.

        :param timeout: number of seconds to wait
        :type timeout: float
        :returns: False if thread was stopped
        :rtype: boolean
        """
        for _ in range(int(timeout) * 5):
            time.sleep(0.2)

            # check if connection was disabled
            if self.stopConnectionThread:
                self.stopConnectionThread = False
                return False

        return True

    def __start_connect_thread(self):
        self.connectionThread = threading.Thread(target=self.__connect_thread,
                                                 name="secsgem_HsmsActiveConnection_connectThread_{}"
                                                 .format(self.remoteAddress))
        self.connectionThread.start()

    def __connect_thread(self):
        """
        Thread function to (re)connect active connection to remote host.

        .. warning:: Do not call this directly, for internal use only.
        """
        # wait for timeout if this is not the first connection
        if not self.firstConnection:
            if not self.__idle(self.T5):
                return
        
        self.firstConnection = False

        # try to connect to remote
        while not self.__connect():
            if not self.__idle(self.T5):
                return

    def __connect(self):
        """
        Open connection to remote host.

        :returns: True if connection was established, False if connection failed
        :rtype: boolean
        """
        # create socket
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

        # setup socket
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)

        if self.delegate != None:
            self.delegate.secsgem_logging("connecting to {}:{}".format(self.remoteAddress, self.remotePort), 'trace')
        
        # try to connect socket
        try:
            self.sock.connect((self.remoteAddress, self.remotePort))
        except socket.error:
            if self.delegate != None:
                self.delegate.secsgem_logging("connecting to {}:{} failed".format(self.remoteAddress, self.remotePort), 'trace')
            return False

        # make socket nonblocking
        self.sock.setblocking(0)

        # start the receiver thread
        self._start_receiver()

        return True


class HsmsHeader:
    """
    Generic HSMS header.

    Base for different specific headers
    """

    def __init__(self, system, session_id):
        """
        Initialize a hsms header.

        :param system: message ID
        :type system: integer
        :param session_id: device / session ID
        :type session_id: integer

        **Example**::

            >>> import secsgem
            >>>
            >>> secsgem.hsms.packets.HsmsHeader(3, 100)
            HsmsHeader({sessionID:0x0064, stream:00, function:00, pType:0x00, sType:0x01, system:0x00000003, \
requireResponse:False})
        """
        self.sessionID = session_id
        self.requireResponse = False
        self.stream = 0x00
        self.function = 0x00
        self.pType = 0x00
        self.sType = 0x01
        self.system = system

    def __str__(self):
        """Generate string representation for an object of this class."""
        return '{sessionID:0x%04x, stream:%02d, function:%02d, pType:0x%02x, sType:0x%02x, system:0x%08x, ' \
               'requireResponse:%r}' % \
            (self.sessionID, self.stream, self.function, self.pType, self.sType, self.system, self.requireResponse)

    def __repr__(self):
        """Generate textual representation for an object of this class."""
        return "%s(%s)" % (self.__class__.__name__, self.__str__())

    def encode(self):
        """
        Encode header to hsms packet.

        :returns: encoded header
        :rtype: string

        **Example**::

            >>> import secsgem
            >>>
            >>> header = secsgem.hsms.packets.HsmsLinktestReqHeader(2)
            >>> secsgem.common.format_hex(header.encode())
            'ff:ff:00:00:00:05:00:00:00:02'

        """
        header_stream = self.stream
        if self.requireResponse:
            header_stream |= 0b10000000

        return struct.pack(">HBBBBL", self.sessionID, header_stream, self.function, self.pType, self.sType, self.system)


class HsmsSelectReqHeader(HsmsHeader):
    """
    Header for Select Request.

    Header for message with SType 1.
    """

    def __init__(self, system):
        """
        Initialize a hsms select request.

        :param system: message ID
        :type system: integer

        **Example**::

            >>> import secsgem
            >>>
            >>> secsgem.hsms.packets.HsmsSelectReqHeader(14)
            HsmsSelectReqHeader({sessionID:0xffff, stream:00, function:00, pType:0x00, sType:0x01, system:0x0000000e, \
requireResponse:False})
        """
        HsmsHeader.__init__(self, system, 0xFFFF)
        self.requireResponse = False
        self.stream = 0x00
        self.function = 0x00
        self.pType = 0x00
        self.sType = 0x01


class HsmsSelectRspHeader(HsmsHeader):
    """
    Header for Select Response.

    Header for message with SType 2.
    """

    def __init__(self, system):
        """
        Initialize a hsms select response.

        :param system: message ID
        :type system: integer

        **Example**::

            >>> import secsgem
            >>>
            >>> secsgem.hsms.packets.HsmsSelectRspHeader(24)
            HsmsSelectRspHeader({sessionID:0xffff, stream:00, function:00, pType:0x00, sType:0x02, system:0x00000018, \
requireResponse:False})
        """
        HsmsHeader.__init__(self, system, 0xFFFF)
        self.requireResponse = False
        self.stream = 0x00
        self.function = 0x00
        self.pType = 0x00
        self.sType = 0x02


class HsmsDeselectReqHeader(HsmsHeader):
    """
    Header for Deselect Request.

    Header for message with SType 3.
    """

    def __init__(self, system):
        """
        Initialize a hsms deselect request.

        :param system: message ID
        :type system: integer

        **Example**::

            >>> import secsgem
            >>>
            >>> secsgem.hsms.packets.HsmsDeselectReqHeader(1)
            HsmsDeselectReqHeader({sessionID:0xffff, stream:00, function:00, pType:0x00, sType:0x03, \
system:0x00000001, requireResponse:False})
        """
        HsmsHeader.__init__(self, system, 0xFFFF)
        self.requireResponse = False
        self.stream = 0x00
        self.function = 0x00
        self.pType = 0x00
        self.sType = 0x03


class HsmsDeselectRspHeader(HsmsHeader):
    """
    Header for Deselect Response.

    Header for message with SType 4.
    """

    def __init__(self, system):
        """
        Initialize a hsms deslelct response.

        :param system: message ID
        :type system: integer

        **Example**::

            >>> import secsgem
            >>>
            >>> secsgem.hsms.packets.HsmsDeselectRspHeader(1)
            HsmsDeselectRspHeader({sessionID:0xffff, stream:00, function:00, pType:0x00, sType:0x04, \
system:0x00000001, requireResponse:False})
        """
        HsmsHeader.__init__(self, system, 0xFFFF)
        self.requireResponse = False
        self.stream = 0x00
        self.function = 0x00
        self.pType = 0x00
        self.sType = 0x04


class HsmsLinktestReqHeader(HsmsHeader):
    """
    Header for Linktest Request.

    Header for message with SType 5.
    """

    def __init__(self, system):
        """
        Initialize a hsms linktest request.

        :param system: message ID
        :type system: integer

        **Example**::

            >>> import secsgem
            >>>
            >>> secsgem.hsms.packets.HsmsLinktestReqHeader(2)
            HsmsLinktestReqHeader({sessionID:0xffff, stream:00, function:00, pType:0x00, sType:0x05, \
system:0x00000002, requireResponse:False})
        """
        HsmsHeader.__init__(self, system, 0xFFFF)
        self.requireResponse = False
        self.stream = 0x00
        self.function = 0x00
        self.pType = 0x00
        self.sType = 0x05


class HsmsLinktestRspHeader(HsmsHeader):
    """
    Header for Linktest Response.

    Header for message with SType 6.
    """

    def __init__(self, system):
        """
        Initialize a hsms linktest response.

        :param system: message ID
        :type system: integer

        **Example**::

            >>> import secsgem
            >>>
            >>> secsgem.hsms.packets.HsmsLinktestRspHeader(10)
            HsmsLinktestRspHeader({sessionID:0xffff, stream:00, function:00, pType:0x00, sType:0x06, \
system:0x0000000a, requireResponse:False})
        """
        HsmsHeader.__init__(self, system, 0xFFFF)
        self.requireResponse = False
        self.stream = 0x00
        self.function = 0x00
        self.pType = 0x00
        self.sType = 0x06


class HsmsRejectReqHeader(HsmsHeader):
    """
    Header for Reject Request.

    Header for message with SType 7.
    """

    def __init__(self, system, s_type, reason):
        """
        Initialize a hsms reject request.

        :param system: message ID
        :type system: integer
        :param s_type: sType of rejected message
        :type s_type: integer
        :param reason: reason for rejection
        :type reason: integer

        **Example**::

            >>> import secsgem
            >>>
            >>> secsgem.hsms.packets.HsmsRejectReqHeader(17, 3, 4)
            HsmsRejectReqHeader({sessionID:0xffff, stream:03, function:04, pType:0x00, sType:0x07, system:0x00000011, \
requireResponse:False})
        """
        HsmsHeader.__init__(self, system, 0xFFFF)
        self.requireResponse = False
        self.stream = s_type
        self.function = reason
        self.pType = 0x00
        self.sType = 0x07


class HsmsSeparateReqHeader(HsmsHeader):
    """
    Header for Separate Request.

    Header for message with SType 9.
    """

    def __init__(self, system):
        """
        Initialize a hsms separate request header.

        :param system: message ID
        :type system: integer

        **Example**::

            >>> import secsgem
            >>>
            >>> secsgem.hsms.packets.HsmsSeparateReqHeader(17)
            HsmsSeparateReqHeader({sessionID:0xffff, stream:00, function:00, pType:0x00, sType:0x09, \
system:0x00000011, requireResponse:False})
        """
        HsmsHeader.__init__(self, system, 0xFFFF)
        self.requireResponse = False
        self.stream = 0x00
        self.function = 0x00
        self.pType = 0x00
        self.sType = 0x09


class HsmsStreamFunctionHeader(HsmsHeader):
    """
    Header for SECS message.

    Header for message with SType 0.
    """

    def __init__(self, system, stream, function, require_response, session_id):
        """
        Initialize a stream function secs header.

        :param system: message ID
        :type system: integer
        :param stream: messages stream
        :type stream: integer
        :param function: messages function
        :type function: integer
        :param require_response: is response expected from remote
        :type require_response: boolean
        :param session_id: device / session ID
        :type session_id: integer

        **Example**::

            >>> import secsgem
            >>>
            >>> secsgem.hsms.packets.HsmsStreamFunctionHeader(22, 1, 1, True, 100)
            HsmsStreamFunctionHeader({sessionID:0x0064, stream:01, function:01, pType:0x00, sType:0x00, \
system:0x00000016, requireResponse:True})
        """
        HsmsHeader.__init__(self, system, session_id)
        self.sessionID = session_id
        self.requireResponse = require_response
        self.stream = stream
        self.function = function
        self.pType = 0x00
        self.sType = 0x00
        self.system = system


class HsmsPacket:
    """
    Class for hsms packet.

    Contains all required data and functions.
    """

    def __init__(self, header=None, data=b""):
        """
        Initialize a hsms packet.

        :param header: header used for this packet
        :type header: :class:`secsgem.hsms.packets.HsmsHeader` and derived
        :param data: data part used for streams and functions (SType 0)
        :type data: string

        **Example**::

            >>> import secsgem
            >>>
            >>> secsgem.hsms.packets.HsmsPacket(secsgem.hsms.packets.HsmsLinktestReqHeader(2))
            HsmsPacket({'header': HsmsLinktestReqHeader({sessionID:0xffff, stream:00, function:00, pType:0x00, \
sType:0x05, system:0x00000002, requireResponse:False}), 'data': ''})
        """
        if header is None:
            self.header = HsmsHeader(0, 0)
        else:
            self.header = header

        self.data = data

    def __str__(self):
        """Generate string representation for an object of this class."""
        data = "'header': " + self.header.__str__()
        return data

    def __repr__(self):
        """Generate textual representation for an object of this class."""
        return "%s({'header': %s, 'data': '%s'})" % (self.__class__.__name__, self.header.__repr__(),
                                                     self.data.decode("utf-8"))

    def encode(self):
        """
        Encode packet data to hsms packet.

        :returns: encoded packet
        :rtype: string

        **Example**::

            >>> import secsgem
            >>>
            >>> packet = secsgem.hsms.packets.HsmsPacket(secsgem.hsms.packets.HsmsLinktestReqHeader(2))
            >>> secsgem.common.format_hex(packet.encode())
            '00:00:00:0a:ff:ff:00:00:00:05:00:00:00:02'

        """
        headerdata = self.header.encode()

        length = len(headerdata) + len(self.data)

        return struct.pack(">L", length) + headerdata + self.data

    @staticmethod
    def decode(text):
        """
        Decode byte array hsms packet to HsmsPacket object.

        :returns: received packet object
        :rtype: :class:`secsgem.hsms.packets.HsmsPacket`

        **Example**::

            >>> import secsgem
            >>>
            >>> packetData = b"\\x00\\x00\\x00\\x0b\\xff\\xff\\x00\\x00\\x00\\x05\\x00\\x00\\x00\\x02"
            >>>
            >>> secsgem.format_hex(packetData)
            '00:00:00:0b:ff:ff:00:00:00:05:00:00:00:02'
            >>>
            >>> secsgem.hsms.packets.HsmsPacket.decode(packetData)
            HsmsPacket({'header': HsmsHeader({sessionID:0xffff, stream:00, function:00, pType:0x00, sType:0x05, \
system:0x00000002, requireResponse:False}), 'data': ''})
        """
        data_length = len(text) - 14
        data_length_text = str(data_length) + "s"

        res = struct.unpack(">LHBBBBL" + data_length_text, text)

        result = HsmsPacket(HsmsHeader(res[6], res[1]))
        result.header.requireResponse = (((res[2] & 0b10000000) >> 7) == 1)
        result.header.stream = res[2] & 0b01111111
        result.header.function = res[3]
        result.header.pType = res[4]
        result.header.sType = res[5]
        result.data = res[7]

        return result


class HsmsHandler:
    """
    Baseclass for creating Host/Equipment models.

    This layer contains the HSMS functionality.
    Inherit from this class and override required functions.
    """

    def __init__(self, address, port, active, session_id, name, custom_connection_handler=None):
        """
        Initialize hsms handler.

        :param address: IP address of remote host
        :type address: string
        :param port: TCP port of remote host
        :type port: integer
        :param active: Is the connection active (*True*) or passive (*False*)
        :type active: boolean
        :param session_id: session / device ID to use for connection
        :type session_id: integer
        :param name: Name of the underlying configuration
        :type name: string
        :param custom_connection_handler: object for connection handling (ie multi server)
        :type custom_connection_handler: :class:`secsgem.hsms.connections.HsmsMultiPassiveServer`

        **Example**::

            import secsgem

            def onConnect(event, data):
                print "Connected"

            client = secsgem.HsmsHandler("10.211.55.33", 5000, True, 0, "test")
            client.events.hsms_connected += onConnect

            client.enable()

            time.sleep(3)

            client.disable()

        """
        self._eventProducer = EventProducer()
        self._eventProducer.targets += self

        self._callback_handler = CallbackHandler()
        self._callback_handler.target = self

        self.address = address
        self.port = port
        self.active = active
        self.sessionID = session_id
        self.name = name

        self.connected = False

        # system id counter
        self.systemCounter = 0

        # repeating linktest variables
        self.linktestTimer = None
        self.linktestTimeout = 30

        # select request thread for active connections, to avoid blocking state changes
        self.selectReqThread = None

        # response queues
        self._systemQueues = {}

        # hsms connection state fsm
        self.is_connected = False
        self.is_selected = False

        # setup connection
        if self.active:
            if custom_connection_handler is None:
                self.connection = HsmsActiveConnection(self.address, self.port, self.sessionID, self)
            else:
                self.connection = custom_connection_handler.create_connection(self.address, self.port,
                                                                              self.sessionID, self)
        else:
            if custom_connection_handler is None:
                self.connection = HsmsPassiveConnection(self.address, self.port, self.sessionID, self)
            else:
                self.connection = custom_connection_handler.create_connection(self.address, self.port,
                                                                              self.sessionID, self)

    @property
    def events(self):
        """Property for event handling."""
        return self._eventProducer

    @property
    def callbacks(self):
        """Property for callback handling."""
        return self._callback_handler

    def get_next_system_counter(self):
        """
        Returns the next System.

        :returns: System for the next command
        :rtype: integer
        """
        self.systemCounter += 1

        if self.systemCounter > ((2 ** 31) - 1):
            self.systemCounter = 0

        return self.systemCounter

    def _sendSelectReqThread(self):
        response = self.send_select_req()
        if response is None:
            self.secsgem_logging("select request failed", 'trace')

    def _start_linktest_timer(self):
        self.linktestTimer = threading.Timer(self.linktestTimeout, self._on_linktest_timer)
        self.linktestTimer.daemon = True  # kill thread automatically on main program termination
        self.linktestTimer.name = "secsgem_hsmsHandler_linktestTimer"
        self.linktestTimer.start()

    def _on_state_connect(self):
        """
        Connection state model got event connect.

        :param data: event attributes
        :type data: object
        """
        # start linktest timer
        self._start_linktest_timer()

        # start select process if connection is active
        if self.active:
            self.selectReqThread = threading.Thread(target=self._sendSelectReqThread,
                                                    name="secsgem_hsmsHandler_sendSelectReqThread")
            self.selectReqThread.daemon = True  # kill thread automatically on main program termination
            self.selectReqThread.start()

    def _on_state_disconnect(self):
        """
        Connection state model got event disconnect.

        :param data: event attributes
        :type data: object
        """
        # stop linktest timer
        if self.linktestTimer:
            self.linktestTimer.cancel()

        self.linktestTimer = None

    def _on_state_select(self):
        """
        Connection state model got event select.

        :param data: event attributes
        :type data: object
        """
        # send event
        self.events.fire('hsms_selected', {'connection': self})

        # notify hsms handler of selection
        if hasattr(self, '_on_hsms_select') and callable(getattr(self, '_on_hsms_select')):
            self._on_hsms_select()

    def _on_linktest_timer(self):
        """Linktest time timed out, so send linktest request."""
        # send linktest request and wait for response
        self.send_linktest_req()

        # restart the timer
        self._start_linktest_timer()

    def on_connection_established(self, _):
        """Connection was established."""
        self.connected = True

        # update connection state
        self.is_connected = True
        self._on_state_connect()

        self.events.fire("hsms_connected", {'connection': self})

    def on_connection_before_closed(self, _):
        """Connection is about to be closed."""
        # send separate request
        self.send_separate_req()

    def on_connection_closed(self, _):
        """Connection was closed."""
        # update connection state
        self.connected = False
        self.is_connected = False
        self._on_state_disconnect()

        self.events.fire("hsms_disconnected", {'connection': self})

    def __handle_hsms_requests(self, packet):
        self.secsgem_logging("__handle_hsms_requests::packet={}\nhsmsSTypes={}".format(packet, hsmsSTypes[packet.header.sType]), 'trace')

        # check if it is a select request
        if packet.header.sType == 0x01:
            # if we are disconnecting send reject else send response
            if self.connection.disconnecting:
                self.send_reject_rsp(packet.header.system, packet.header.sType, 4)
            else:
                self.send_select_rsp(packet.header.system)

                # update connection state
                self.is_selected = True
                self._on_state_select()

        # check if it is a select response
        elif packet.header.sType == 0x02:
            # update connection state
            self.is_selected = True
            self._on_state_select()

            if packet.header.system in self._systemQueues:
                # send packet to request sender
                self._systemQueues[packet.header.system].put_nowait(packet)

            # what to do if no sender for request waiting?

        # check if it is a deselect request
        elif packet.header.sType == 0x03:
            # if we are disconnecting send reject else send response
            if self.connection.disconnecting:
                self.send_reject_rsp(packet.header.system, packet.header.sType, 4)
            else:
                self.send_deselect_rsp(packet.header.system)
                # update connection state
                self.is_selected = False

        # check if it is a deselect response
        elif packet.header.sType == 0x04:
            # update connection state
            self.is_selected = False

            if packet.header.system in self._systemQueues:
                # send packet to request sender
                self._systemQueues[packet.header.system].put_nowait(packet)

            # what to do if no sender for request waiting?

        # check if it is a linktest request
        elif packet.header.sType == 0x05:
            # if we are disconnecting send reject else send response
            if self.connection.disconnecting:
                self.send_reject_rsp(packet.header.system, packet.header.sType, 4)
            else:
                self.send_linktest_rsp(packet.header.system)

        else:
            if packet.header.system in self._systemQueues:
                # send packet to request sender
                self._systemQueues[packet.header.system].put_nowait(packet)

            # what to do if no sender for request waiting?

    def on_connection_packet_received(self, _, packet):
        """
        Packet received by connection.

        :param packet: received data packet
        :type packet: :class:`secsgem.hsms.packets.HsmsPacket`
        """
        if packet.header.sType > 0:
            self.__handle_hsms_requests(packet)
        else:
            if hasattr(self, 'secs_decode') and callable(getattr(self, 'secs_decode')):
                message = self.secs_decode(packet)
                self.secsgem_logging("on_connection_packet_received::packet={}\nmessage={}".format(packet, message), 'trace')
            else:
                self.secsgem_logging("on_connection_packet_received::packet={}".format(packet), 'trace')

            if not self.is_connected and not self.is_selected:
                self.secsgem_logging("received message when not selected", 'trace')

                out_packet = HsmsPacket(HsmsRejectReqHeader(packet.header.system, packet.header.sType, 4))
                self.secsgem_logging("on_connection_packet_received::out_packet={}\nhsmsSTypes={}".format(out_packet, hsmsSTypes[out_packet.header.sType]), 'trace')
                self.connection.send_packet(out_packet)

                return

            # someone is waiting for this message
            if packet.header.system in self._systemQueues:
                # send packet to request sender
                self._systemQueues[packet.header.system].put_nowait(packet)
            # redirect packet to hsms handler
            elif hasattr(self, '_on_hsms_packet_received') and callable(getattr(self, '_on_hsms_packet_received')):
                self._on_hsms_packet_received(packet)
            # just log if nobody is interested
            else:
                self.secsgem_logging("packet unhandled", 'trace')

    def _get_queue_for_system(self, system_id):
        """
        Creates a new queue to receive responses for a certain system.

        :param system_id: system id to watch
        :type system_id: int
        :returns: queue to receive responses with
        :rtype: queue.Queue
        """
        self._systemQueues[system_id] = queue.Queue()
        return self._systemQueues[system_id]

    def _remove_queue(self, system_id):
        """
        Remove queue for system id from list.

        :param system_id: system id to remove
        :type system_id: int
        """
        del self._systemQueues[system_id]

    def __repr__(self):
        """Generate textual representation for an object of this class."""
        return "{} {}".format(self.__class__.__name__, str(self._serialize_data()))

    def _serialize_data(self):
        """
        Returns data for serialization.

        :returns: data to serialize for this object
        :rtype: dict
        """
        return {'address': self.address, 'port': self.port, 'active': self.active, 'sessionID': self.sessionID,
                'name': self.name, 'connected': self.connected}

    def enable(self):
        """Enables the connection."""
        self.connection.enable()

    def disable(self):
        """Disables the connection."""
        self.connection.disable()

    def send_stream_function(self, packet):
        """
        Send the packet and wait for the response.

        :param packet: packet to be sent
        :type packet: :class:`secsgem.secs.functionbase.SecsStreamFunction`
        """
        out_packet = HsmsPacket(
            HsmsStreamFunctionHeader(self.get_next_system_counter(), packet.stream, packet.function,
                                     packet.is_reply_required, self.sessionID),
            packet.encode())

        self.secsgem_logging("send_stream_function::out_packet={}\npacket={}".format(out_packet, packet), 'trace')

        return self.connection.send_packet(out_packet)

    def send_and_waitfor_response(self, packet):
        """
        Send the packet and wait for the response.

        :param packet: packet to be sent
        :type packet: :class:`secsgem.secs.functionbase.SecsStreamFunction`
        :returns: Packet that was received
        :rtype: :class:`secsgem.hsms.packets.HsmsPacket`
        """
        system_id = self.get_next_system_counter()

        response_queue = self._get_queue_for_system(system_id)

        out_packet = HsmsPacket(HsmsStreamFunctionHeader(system_id, packet.stream, packet.function, True,
                                                         self.sessionID),
                                packet.encode())

        self.secsgem_logging("send_and_waitfor_response::out_packet={}\npacket={}".format(out_packet, packet), 'trace')

        if not self.connection.send_packet(out_packet):
            self.secsgem_logging("Sending packet failed", 'trace')
            self._remove_queue(system_id)
            return None

        try:
            response = response_queue.get(True, self.connection.T3)
        except queue.Empty:
            response = None

        self._remove_queue(system_id)

        return response

    def send_response(self, function, system):
        """
        Send response function for system.

        :param function: function to be sent
        :type function: :class:`secsgem.secs.functionbase.SecsStreamFunction`
        :param system: system to reply to
        :type system: integer
        """
        out_packet = HsmsPacket(HsmsStreamFunctionHeader(system, function.stream, function.function, False,
                                                         self.sessionID),
                                function.encode())

        self.secsgem_logging("send_response::out_packet={}\nfunction={}".format(out_packet, function), 'trace')

        return self.connection.send_packet(out_packet)

    def send_select_req(self):
        """
        Send a Select Request to the remote host.

        :returns: System of the sent request
        :rtype: integer
        """
        system_id = self.get_next_system_counter()

        response_queue = self._get_queue_for_system(system_id)

        packet = HsmsPacket(HsmsSelectReqHeader(system_id))
        self.secsgem_logging("send_select_req::packet={}\nhsmsSTypes={}".format(packet, hsmsSTypes[packet.header.sType]), 'trace')

        if not self.connection.send_packet(packet):
            self._remove_queue(system_id)
            return None

        try:
            response = response_queue.get(True, self.connection.T6)
        except queue.Empty:
            response = None

        self._remove_queue(system_id)

        return response

    def send_select_rsp(self, system_id):
        """
        Send a Select Response to the remote host.

        :param system_id: System of the request to reply for
        :type system_id: integer
        """
        packet = HsmsPacket(HsmsSelectRspHeader(system_id))
        self.secsgem_logging("send_select_rsp::packet={}\nhsmsSTypes={}".format(packet, hsmsSTypes[packet.header.sType]), 'trace')

        return self.connection.send_packet(packet)

    def send_linktest_req(self):
        """
        Send a Linktest Request to the remote host.

        :returns: System of the sent request
        :rtype: integer
        """
        system_id = self.get_next_system_counter()

        response_queue = self._get_queue_for_system(system_id)

        packet = HsmsPacket(HsmsLinktestReqHeader(system_id))
        self.secsgem_logging("send_linktest_req::packet={}\nhsmsSTypes={}".format(packet, hsmsSTypes[packet.header.sType]), 'trace')

        if not self.connection.send_packet(packet):
            self._remove_queue(system_id)
            return None

        try:
            response = response_queue.get(True, self.connection.T6)
        except queue.Empty:
            response = None

        self._remove_queue(system_id)

        return response

    def send_linktest_rsp(self, system_id):
        """
        Send a Linktest Response to the remote host.

        :param system_id: System of the request to reply for
        :type system_id: integer
        """
        packet = HsmsPacket(HsmsLinktestRspHeader(system_id))
        self.secsgem_logging("send_linktest_rsp::packet={}\nhsmsSTypes={}".format(packet, hsmsSTypes[packet.header.sType]), 'trace')
        return self.connection.send_packet(packet)

    def send_deselect_req(self):
        """
        Send a Deselect Request to the remote host.

        :returns: System of the sent request
        :rtype: integer
        """
        system_id = self.get_next_system_counter()

        response_queue = self._get_queue_for_system(system_id)

        packet = HsmsPacket(HsmsDeselectReqHeader(system_id))
        self.secsgem_logging("send_deselect_req::packet={}\nhsmsSTypes={}".format(packet, hsmsSTypes[packet.header.sType]), 'trace')

        if not self.connection.send_packet(packet):
            self._remove_queue(system_id)
            return None

        try:
            response = response_queue.get(True, self.connection.T6)
        except queue.Empty:
            response = None

        self._remove_queue(system_id)

        return response

    def send_deselect_rsp(self, system_id):
        """
        Send a Deselect Response to the remote host.

        :param system_id: System of the request to reply for
        :type system_id: integer
        """
        packet = HsmsPacket(HsmsDeselectRspHeader(system_id))
        self.secsgem_logging("send_deselect_rsp::packet={}\nhsmsSTypes={}".format(packet, hsmsSTypes[packet.header.sType]), 'trace')
        return self.connection.send_packet(packet)

    def send_reject_rsp(self, system_id, s_type, reason):
        """
        Send a Reject Response to the remote host.

        :param system_id: System of the request to reply for
        :type system_id: integer
        :param s_type: s_type of rejected message
        :type s_type: integer
        :param reason: reason for rejection
        :type reason: integer
        """
        packet = HsmsPacket(HsmsRejectReqHeader(system_id, s_type, reason))
        self.secsgem_logging("send_reject_rsp::packet={}\nhsmsSTypes={}".format(packet, hsmsSTypes[packet.header.sType]), 'trace')
        return self.connection.send_packet(packet)

    def send_separate_req(self):
        """Send a Separate Request to the remote host."""
        system_id = self.get_next_system_counter()

        packet = HsmsPacket(HsmsSeparateReqHeader(system_id))
        self.secsgem_logging("send_separate_req::packet={}\nhsmsSTypes={}".format(packet, hsmsSTypes[packet.header.sType]), 'trace')

        if not self.connection.send_packet(packet):
            return None

        return system_id

class SecsHandler(HsmsHandler):
    """
    Baseclass for creating Host/Equipment models. This layer contains the SECS functionality.

    Inherit from this class and override required functions.
    """

    def __init__(self, address, port, active, session_id, name, custom_connection_handler=None):
        """
        Initialize a secs handler.

        :param address: IP address of remote host
        :type address: string
        :param port: TCP port of remote host
        :type port: integer
        :param active: Is the connection active (*True*) or passive (*False*)
        :type active: boolean
        :param session_id: session / device ID to use for connection
        :type session_id: integer
        :param name: Name of the underlying configuration
        :type name: string
        :param custom_connection_handler: object for connection handling (ie multi server)
        :type custom_connection_handler: :class:`secsgem.hsms.connections.HsmsMultiPassiveServer`
        """
        HsmsHandler.__init__(self, address, port, active, session_id, name, custom_connection_handler)

        self._collectionEvents = {}
        self._dataValues = {}
        self._alarms = {}
        self._remoteCommands = {}

        self.secsStreamsFunctions = copy.deepcopy(secsStreamsFunctions)

    def _generate_sf_callback_name(self, stream, function):
        return "s{stream:02d}f{function:02d}".format(stream=stream, function=function)

    def register_stream_function(self, stream, function, callback):
        """
        Register the function callback for stream and function.

        :param stream: stream to register callback for
        :type stream: integer
        :param function: function to register callback for
        :type function: integer
        :param callback: method to call when stream and functions is received
        :type callback: def callback(connection)
        """
        name = self._generate_sf_callback_name(stream, function)
        setattr(self._callback_handler, name, callback)

    def unregister_stream_function(self, stream, function):
        """
        Unregister the function callback for stream and function.

        :param stream: stream to unregister callback for
        :type stream: integer
        :param function: function to register callback for
        :type function: integer
        """
        name = self._generate_sf_callback_name(stream, function)
        setattr(self._callback_handler, name, None)

    @property
    def collection_events(self):
        """
        Dictionary of available collection events.

        *Example*::

            >>> handler = SecsHandler("127.0.0.1", 5000, False, 0, "test")
            >>> handler.collection_events[123] = {'name': 'collectionEventName', 'dvids': [1, 5] }

        **Key**

        Id of the collection event (integer)

        **Data**

        Dictionary with the following fields

            name
                Name of the collection event (string)

            dvids
                Data values for the collection event (list of integers)

        """
        return self._collectionEvents

    @property
    def data_values(self):
        """
        Dictionary of available data values.

        *Example*::

            >>> handler = SecsHandler("127.0.0.1", 5000, False, 0, "test")
            >>> handler.data_values[5] = {'name': 'dataValueName', 'ceid': 123 }

        **Key**

        Id of the data value (integer)

        **Data**

        Dictionary with the following fields

            name
                Name of the data value (string)

            ceid
                Collection event the data value is used for (integer)

        """
        return self._dataValues

    @property
    def alarms(self):
        """
        Dictionary of available alarms.

        *Example*::

            >>> handler = SecsHandler("127.0.0.1", 5000, True, 0, "test")
            >>> handler.alarms[137] = {'ceidon': 1371, 'ceidoff': 1372}

        **Key**

        Id of the alarm (integer)

        **Data**

        Dictionary with the following fields

            ceidon
                Collection event id for alarm on (integer)

            ceidoff
                Collection event id for alarm off (integer)

        """
        return self._alarms

    @property
    def remote_commands(self):
        """
        Dictionary of available remote commands.

        *Example*::

            >>> handler = SecsHandler("127.0.0.1", 5000, True, 0, "test")
            >>> handler.remote_commands["PP_SELECT"] = {'params': [{'name': 'PROGRAM', 'format': 'A'}], \
'ceids': [200, 343]}

        **Key**

        Name of the remote command (string)

        **Data**

        Dictionary with the following fields

            params
                Parameters for the remote command (list of dictionaries)

                *Parameters*

                    The dictionaries have the following fields

                    name
                        name of the parameter (string)

                    format
                        format character of the parameter (string)

            ceids
                Collection events ids the remote command might return (list of integers)

        """
        return self._remoteCommands

    def _handle_stream_function(self, packet):
        sf_callback_index = self._generate_sf_callback_name(packet.header.stream, packet.header.function)

        # return S09F05 if no callback present
        if sf_callback_index not in self._callback_handler:
            self.secsgem_logging("unexpected function received {}\n{}".format(sf_callback_index, packet.header), 'trace')
            if packet.header.requireResponse:
                self.send_response(self.stream_function(9, 5)(packet.header.encode()), packet.header.system)

            return

        try:
            callback = getattr(self._callback_handler, sf_callback_index)
            result = callback(self, packet)
            if result is not None:
                self.send_response(result, packet.header.system)
        except Exception:
            self.secsgem_logging('Callback aborted because of exception, abort sent', 'trace')
            self.send_response(self.stream_function(packet.header.stream, 0)(), packet.header.system)

    def _on_hsms_packet_received(self, packet):
        """
        Packet received from hsms layer.

        :param packet: received data packet
        :type packet: :class:`secsgem.hsms.packets.HsmsPacket`
        """
        # check if callbacks available for this stream and function
        threading.Thread(
            target=self._handle_stream_function, args=(packet, ),
            name="secsgem_secsHandler_callback_S{}F{}".format(packet.header.stream, packet.header.function)).start()

    def disable_ceids(self):
        """Disable all Collection Events."""
        self.secsgem_logging("Disable all collection events", 'trace')

        return self.send_and_waitfor_response(self.stream_function(2, 37)({"CEED": False, "CEID": []}))

    def disable_ceid_reports(self):
        """Disable all Collection Event Reports."""
        self.secsgem_logging("Disable all collection event reports", 'trace')

        return self.send_and_waitfor_response(self.stream_function(2, 33)({"DATAID": 0, "DATA": []}))

    def list_svs(self, svs=None):
        """
        Get list of available Service Variables.

        :returns: available Service Variables
        :rtype: list
        """
        self.secsgem_logging("Get list of service variables", 'trace')

        if svs is None:
            svs = []

        packet = self.send_and_waitfor_response(self.stream_function(1, 11)(svs))

        return self.secs_decode(packet)

    def request_svs(self, svs):
        """
        Request contents of supplied Service Variables.

        :param svs: Service Variables to request
        :type svs: list
        :returns: values of requested Service Variables
        :rtype: list
        """
        self.secsgem_logging("Get value of service variables {}".format(svs), 'trace')

        packet = self.send_and_waitfor_response(self.stream_function(1, 3)(svs))

        return self.secs_decode(packet)

    def request_sv(self, sv):
        """
        Request contents of one Service Variable.

        :param sv: id of Service Variable
        :type sv: int
        :returns: value of requested Service Variable
        :rtype: various
        """
        self.secsgem_logging("Get value of service variable {}".format(sv), 'trace')

        return self.request_svs([sv])[0]

    def list_ecs(self, ecs=None):
        """
        Get list of available Equipment Constants.

        :returns: available Equipment Constants
        :rtype: list
        """
        self.secsgem_logging("Get list of equipment constants", 'trace')

        if ecs is None:
            ecs = []
        packet = self.send_and_waitfor_response(self.stream_function(2, 29)(ecs))

        return self.secs_decode(packet)

    def request_ecs(self, ecs):
        """
        Request contents of supplied Equipment Constants.

        :param ecs: Equipment Constants to request
        :type ecs: list
        :returns: values of requested Equipment Constants
        :rtype: list
        """
        self.secsgem_logging("Get value of equipment constants {}".format(ecs), 'trace')

        packet = self.send_and_waitfor_response(self.stream_function(2, 13)(ecs))

        return self.secs_decode(packet)

    def request_ec(self, ec):
        """
        Request contents of one Equipment Constant.

        :param ec: id of Equipment Constant
        :type ec: int
        :returns: value of requested Equipment Constant
        :rtype: various
        """
        self.secsgem_logging("Get value of equipment constant {}".format(ec), 'trace')

        return self.request_ecs([ec])

    def set_ecs(self, ecs):
        """
        Set contents of supplied Equipment Constants.

        :param ecs: list containing list of id / value pairs
        :type ecs: list
        """
        self.secsgem_logging("Set value of equipment constants {}".format(ecs), 'trace')

        packet = self.send_and_waitfor_response(self.stream_function(2, 15)(ecs))

        return self.secs_decode(packet).get()

    def set_ec(self, ec, value):
        """
        Set contents of one Equipment Constant.

        :param ec: id of Equipment Constant
        :type ec: int
        :param value: new content of Equipment Constant
        :type value: various
        """
        self.secsgem_logging("Set value of equipment constant {} to {}".format(ec, value), 'trace')

        return self.set_ecs([[ec, value]])

    def send_equipment_terminal(self, terminal_id, text):
        """
        Set text to equipment terminal.

        :param terminal_id: ID of terminal
        :type terminal_id: int
        :param text: text to send
        :type text: string
        """
        self.secsgem_logging("Send text to terminal {}".format(terminal_id), 'trace')

        return self.send_and_waitfor_response(self.stream_function(10, 3)({"TID": terminal_id, "TEXT": text}))

    def get_ceid_name(self, ceid):
        """
        Get the name of a collection event.

        :param ceid: ID of collection event
        :type ceid: integer
        :returns: Name of the event or empty string if not found
        :rtype: string
        """
        if ceid in self._collectionEvents:
            if "name" in self._collectionEvents[ceid]:
                return self._collectionEvents[ceid]["name"]

        return ""

    def get_dvid_name(self, dvid):
        """
        Get the name of a data value.

        :param dvid: ID of data value
        :type dvid: integer
        :returns: Name of the event or empty string if not found
        :rtype: string
        """
        if dvid in self._dataValues:
            if "name" in self._dataValues[dvid]:
                return self._dataValues[dvid]["name"]

        return ""

    def are_you_there(self):
        """Check if remote is still replying."""
        self.secsgem_logging("Requesting 'are you there'", 'trace')

        return self.send_and_waitfor_response(self.stream_function(1, 1)())

    def stream_function(self, stream, function):
        """
        Get class for stream and function.

        :param stream: stream to get function for
        :type stream: int
        :param function: function to get
        :type function: int
        :return: matching stream and function class
        :rtype: secsSxFx class
        """
        if stream not in self.secsStreamsFunctions:
            self.secsgem_logging("unknown function S{}F{}".format(stream, function), 'trace')
            return None

        if function not in self.secsStreamsFunctions[stream]:
            self.secsgem_logging("unknown function S{}F{}".format(stream, function), 'trace')
            return None

        return self.secsStreamsFunctions[stream][function]

    def secs_decode(self, packet):
        """
        Get object of decoded stream and function class, or None if no class is available.

        :param packet: packet to get object for
        :type packet: :class:`secsgem.hsms.packets.HsmsPacket`
        :return: matching stream and function object
        :rtype: secsSxFx object
        """
        if packet is None:
            return None

        if packet.header.stream not in self.secsStreamsFunctions:
            self.secsgem_logging("unknown function S{}F{}".format(packet.header.stream, packet.header.function), 'trace')
            return None

        if packet.header.function not in self.secsStreamsFunctions[packet.header.stream]:
            self.secsgem_logging("unknown function S{}F{}".format(packet.header.stream, packet.header.function), 'trace')
            return None

        function = self.secsStreamsFunctions[packet.header.stream][packet.header.function]()
        function.decode(packet.data)
        self.secsgem_logging('received raw data:: ' + str(function), 'debug')
        return function


class GemHandler(SecsHandler):
    """Baseclass for creating Host/Equipment models. This layer contains GEM functionality."""

    def __init__(self, address, port, active, session_id, name, custom_connection_handler=None):
        """
        Initialize a gem handler.

        Inherit from this class and override required functions.

        :param address: IP address of remote host
        :type address: string
        :param port: TCP port of remote host
        :type port: integer
        :param active: Is the connection active (*True*) or passive (*False*)
        :type active: boolean
        :param session_id: session / device ID to use for connection
        :type session_id: integer
        :param name: Name of the underlying configuration
        :type name: string
        :param custom_connection_handler: object for connection handling (ie multi server)
        :type custom_connection_handler: :class:`secsgem.hsms.connections.HsmsMultiPassiveServer`
        """
        SecsHandler.__init__(self, address, port, active, session_id, name, custom_connection_handler)

        self.MDLN = "secsgem"  #: model number returned by S01E13/14
        self.SOFTREV = "0.1.0"  #: software version returned by S01E13/14
        
        self.isHost = True

        # not going to HOST_INITIATED_CONNECT because fysom doesn't support two states.
        # but there is a transistion to get out of EQUIPMENT_INITIATED_CONNECT when the HOST_INITIATED_CONNECT happens
        self.communicationState = Fysom({
            'initial': 'DISABLED',  # 1
            'events': [
                {'name': 'enable', 'src': 'DISABLED', 'dst': 'ENABLED'},  # 2
                {'name': 'disable', 'src': ['ENABLED', 'NOT_COMMUNICATING', 'COMMUNICATING',
                                            'EQUIPMENT_INITIATED_CONNECT', 'WAIT_DELAY', 'WAIT_CRA',
                                            "HOST_INITIATED_CONNECT", "WAIT_CR_FROM_HOST"], 'dst': 'DISABLED'},  # 3
                {'name': 'select', 'src': 'NOT_COMMUNICATING', 'dst': 'EQUIPMENT_INITIATED_CONNECT'},  # 5
                {'name': 'communicationreqfail', 'src': 'WAIT_CRA', 'dst': 'WAIT_DELAY'},  # 6
                {'name': 'delayexpired', 'src': 'WAIT_DELAY', 'dst': 'WAIT_CRA'},  # 7
                {'name': 'messagereceived', 'src': 'WAIT_DELAY', 'dst': 'WAIT_CRA'},  # 8
                {'name': 's1f14received', 'src': 'WAIT_CRA', 'dst': 'COMMUNICATING'},  # 9
                {'name': 'communicationfail', 'src': 'COMMUNICATING', 'dst': 'NOT_COMMUNICATING'},  # 14
                # 15 (WAIT_CR_FROM_HOST is running in background - AND state -
                # so if s1f13 is received we go all communicating)
                {'name': 's1f13received', 'src': ['WAIT_CR_FROM_HOST', 'WAIT_DELAY', 'WAIT_CRA'],
                 'dst': 'COMMUNICATING'},
            ],
            'callbacks': {
                'onWAIT_CRA': self._on_state_wait_cra,
                'onWAIT_DELAY': self._on_state_wait_delay,
                'onleaveWAIT_CRA': self._on_state_leave_wait_cra,
                'onleaveWAIT_DELAY': self._on_state_leave_wait_delay,
                'onCOMMUNICATING': self._on_state_communicating,
                # 'onselect': self.onStateSelect,
            },
            'autoforward': [
                {'src': 'ENABLED', 'dst': 'NOT_COMMUNICATING'},  # 4
                {'src': 'EQUIPMENT_INITIATED_CONNECT', 'dst': 'WAIT_CRA'},  # 5
                {'src': 'HOST_INITIATED_CONNECT', 'dst': 'WAIT_CR_FROM_HOST'},  # 10
            ]
        })

        self.waitCRATimer = None
        self.commDelayTimer = None
        self.establishCommunicationTimeout = 10

        self.reportIDCounter = 1000

        self.waitEventList = []

    def __repr__(self):
        """Generate textual representation for an object of this class."""
        return "{} {}".format(self.__class__.__name__, str(self._serialize_data()))

    def _serialize_data(self):
        """
        Returns data for serialization.

        :returns: data to serialize for this object
        :rtype: dict
        """
        data = SecsHandler._serialize_data(self)
        data.update({'communicationState': self.communicationState.current,
                     'commDelayTimeout': self.establishCommunicationTimeout,
                     'reportIDCounter': self.reportIDCounter})
        return data

    def enable(self):
        """Enables the connection."""
        self.connection.enable()
        self.communicationState.enable()

        self.secsgem_logging("Connection enabled", 'trace')

    def disable(self):
        """Disables the connection."""
        self.connection.disable()
        try:
            self.communicationState.disable()
        except Exception as e:
            self.secsgem_logging(str(e), 'error')

        self.secsgem_logging("Connection disabled", 'trace')

    def _on_hsms_packet_received(self, packet):
        """
        Packet received from hsms layer.

        :param packet: received data packet
        :type packet: :class:`secsgem.HsmsPacket`
        """
        if self.communicationState.isstate('WAIT_CRA'):
            if packet.header.stream == 1 and packet.header.function == 13:
                if self.isHost:
                    self.send_response(self.stream_function(1, 14)({"COMMACK": self.on_commack_requested(),
                                                                    "MDLN": []}),
                                       packet.header.system)
                else:
                    self.send_response(self.stream_function(1, 14)({"COMMACK": self.on_commack_requested(),
                                                                    "MDLN": [self.MDLN, self.SOFTREV]}),
                                       packet.header.system)

                self.communicationState.s1f13received()
            elif packet.header.stream == 1 and packet.header.function == 14:
                self.communicationState.s1f14received()
        elif self.communicationState.isstate('WAIT_DELAY'):
            pass
        elif self.communicationState.isstate('COMMUNICATING'):
            threading.Thread(target=self._handle_stream_function, args=(packet, ),
                             name="secsgem_gemHandler_callback_S{}F{}".format(packet.header.stream,
                                                                              packet.header.function)).start()

    def _on_hsms_select(self):
        """Selected received from hsms layer."""
        self.communicationState.select()

    def _on_wait_cra_timeout(self):
        """Linktest time timed out, so send linktest request."""
        self.communicationState.communicationreqfail()

    def _on_wait_comm_delay_timeout(self):
        """Linktest time timed out, so send linktest request."""
        self.communicationState.delayexpired()

    def _on_state_wait_cra(self, _):
        """
        Connection state model changed to state WAIT_CRA.

        :param data: event attributes
        :type data: object
        """
        self.secsgem_logging("connectionState -> WAIT_CRA", 'trace')

        self.waitCRATimer = threading.Timer(self.connection.T3, self._on_wait_cra_timeout)
        self.waitCRATimer.start()

        if self.isHost:
            self.send_stream_function(self.stream_function(1, 13)())
        else:
            self.send_stream_function(self.stream_function(1, 13)([self.MDLN, self.SOFTREV]))

    def _on_state_wait_delay(self, _):
        """
        Connection state model changed to state WAIT_DELAY.

        :param data: event attributes
        :type data: object
        """
        self.secsgem_logging("connectionState -> WAIT_DELAY", 'trace')

        self.commDelayTimer = threading.Timer(self.establishCommunicationTimeout, self._on_wait_comm_delay_timeout)
        self.commDelayTimer.start()

    def _on_state_leave_wait_cra(self, _):
        """
        Connection state model changed to state WAIT_CRA.

        :param data: event attributes
        :type data: object
        """
        if self.waitCRATimer is not None:
            self.waitCRATimer.cancel()

    def _on_state_leave_wait_delay(self, _):
        """
        Connection state model changed to state WAIT_DELAY.

        :param data: event attributes
        :type data: object
        """
        if self.commDelayTimer is not None:
            self.commDelayTimer.cancel()

    def _on_state_communicating(self, _):
        """
        Connection state model changed to state COMMUNICATING.

        :param data: event attributes
        :type data: object
        """
        self.secsgem_logging("connectionState -> COMMUNICATING", 'trace')

        self.events.fire("handler_communicating", {'handler': self})

        for event in self.waitEventList:
            event.set()

    def on_connection_closed(self, connection):
        """Connection was closed."""
        self.secsgem_logging("Connection was closed", 'trace')

        # call parent handlers
        SecsHandler.on_connection_closed(self, connection)

        if self.communicationState.current == "COMMUNICATING":
            # update communication state
            self.communicationState.communicationfail()

    def on_commack_requested(self):
        """
        Get the acknowledgement code for the connection request.

        override to accept or deny connection request

        :returns: 0 when connection is accepted, 1 when connection is denied
        :rtype: integer
        """
        return 0

    def send_process_program(self, ppid, ppbody):
        """
        Send a process program.

        :param ppid: Transferred process programs ID
        :type ppid: string
        :param ppbody: Content of process program
        :type ppbody: string
        """
        self.secsgem_logging("Send process program {}".format(ppid), 'trace')

        return self.secs_decode(self.send_and_waitfor_response(self.stream_function(7, 3)(
            {"PPID": ppid, "PPBODY": ppbody}))).get()

    def request_process_program(self, ppid):
        """
        Request a process program.

        :param ppid: Transferred process programs ID
        :type ppid: string
        """
        self.secsgem_logging("Request process program {}".format(ppid), 'trace')

        # send remote command
        s7f6 = self.secs_decode(self.send_and_waitfor_response(self.stream_function(7, 5)(ppid)))
        return s7f6.PPID.get(), s7f6.PPBODY.get()

    def waitfor_communicating(self, timeout=None):
        """
        Wait until connection gets into communicating state. Returns immediately if state is communicating.

        :param timeout: seconds to wait before aborting
        :type timeout: float
        :returns: True if state is communicating, False if timed out
        :rtype: bool
        """
        event = threading.Event()
        self.waitEventList.append(event)

        if self.communicationState.isstate("COMMUNICATING"):
            self.waitEventList.remove(event)
            return True

        result = event.wait(timeout)

        self.waitEventList.remove(event)

        return result

    def _on_s01f01(self, handler, packet):
        """
        Callback handler for Stream 1, Function 1, Are You There.

        :param handler: handler the message was received on
        :type handler: :class:`secsgem.hsms.handler.HsmsHandler`
        :param packet: complete message received
        :type packet: :class:`secsgem.hsms.packets.HsmsPacket`
        """
        del handler, packet  # unused parameters

        if self.isHost:
            return self.stream_function(1, 2)()

        return self.stream_function(1, 2)([self.MDLN, self.SOFTREV])

    def _on_s01f13(self, handler, packet):
        """
        Callback handler for Stream 1, Function 13, Establish Communication Request.

        :param handler: handler the message was received on
        :type handler: :class:`secsgem.hsms.handler.HsmsHandler`
        :param packet: complete message received
        :type packet: :class:`secsgem.hsms.packets.HsmsPacket`
        """
        del handler, packet  # unused parameters

        if self.isHost:
            return self.stream_function(1, 14)({"COMMACK": self.on_commack_requested(), "MDLN": []})

        return self.stream_function(1, 14)({"COMMACK": self.on_commack_requested(),
                                            "MDLN": [self.MDLN, self.SOFTREV]})


ECID_ESTABLISH_COMMUNICATIONS_TIMEOUT = 1
ECID_TIME_FORMAT = 2

SVID_CLOCK = 1001
SVID_CONTROL_STATE = 1002
SVID_EVENTS_ENABLED = 1003
SVID_ALARMS_ENABLED = 1004
SVID_ALARMS_SET = 1005

CEID_EQUIPMENT_OFFLINE = 1
CEID_CONTROL_STATE_LOCAL = 2
CEID_CONTROL_STATE_REMOTE = 3

CEID_CMD_START_DONE = 20
CEID_CMD_STOP_DONE = 21

RCMD_START = "START"
RCMD_STOP = "STOP"


class DataValue:
    def __init__(self, dvid, name, value_type, use_callback=True, **kwargs):
        self.dvid = dvid
        self.name = name
        self.value_type = value_type
        self.use_callback = use_callback
        self.value = 0

        if isinstance(self.dvid, int):
            self.id_type = SecsVarU4
        else:
            self.id_type = SecsVarString

        for key, value in kwargs.items():
            setattr(self, key, value)


class StatusVariable:
    def __init__(self, svid, name, unit, value_type, use_callback=True, **kwargs):
        self.svid = svid
        self.name = name
        self.unit = unit
        self.value_type = value_type
        self.use_callback = use_callback
        self.value = 0

        if isinstance(self.svid, int):
            self.id_type = SecsVarU4
        else:
            self.id_type = SecsVarString

        for key, value in kwargs.items():
            setattr(self, key, value)


class CollectionEvent:
    def __init__(self, ceid, name, data_values, **kwargs):
        self.ceid = ceid
        self.name = name
        self.data_values = data_values

        if isinstance(self.ceid, int):
            self.id_type = SecsVarU4
        else:
            self.id_type = SecsVarString

        for key, value in kwargs.items():
            setattr(self, key, value)


class CollectionEventLink:
    def __init__(self, ce, reports, **kwargs):
        self.ce = ce
        self._reports = reports
        self.enabled = False

        for key, value in kwargs.items():
            setattr(self, key, value)

    @property
    def reports(self):
        return self._reports


class CollectionEventReport:
    def __init__(self, rptid, variables, **kwargs):
        self.rptid = rptid
        self.vars = variables

        if isinstance(self.rptid, int):
            self.id_type = SecsVarU4
        else:
            self.id_type = SecsVarString

        for key, value in kwargs.items():
            setattr(self, key, value)


class EquipmentConstant:
    def __init__(self, ecid, name, min_value, max_value, default_value, unit, value_type, use_callback=True, **kwargs):
        self.ecid = ecid
        self.name = name
        self.min_value = min_value
        self.max_value = max_value
        self.default_value = default_value
        self.unit = unit
        self.value_type = value_type
        self.use_callback = use_callback
        self.value = default_value

        if isinstance(self.ecid, int):
            self.id_type = SecsVarU4
        else:
            self.id_type = SecsVarString

        for key, value in kwargs.items():
            setattr(self, key, value)


class Alarm:
    def __init__(self, alid, name, text, code, ce_on, ce_off, **kwargs):
        self.alid = alid
        self.name = name
        self.text = text
        self.code = code
        self.ce_on = ce_on
        self.ce_off = ce_off
        self.enabled = False
        self.set = False

        if isinstance(self.alid, int):
            self.id_type = SecsVarU4
        else:
            self.id_type = SecsVarString

        for key, value in kwargs.items():
            setattr(self, key, value)


class RemoteCommand:
    def __init__(self, rcmd, name, params, ce_finished, **kwargs):
        self.rcmd = rcmd
        self.name = name
        self.params = params
        self.ce_finished = ce_finished

        if isinstance(self.rcmd, int):
            self.id_type = SecsVarU4
        else:
            self.id_type = SecsVarString

        for key, value in kwargs.items():
            setattr(self, key, value)


class GemEquipmentHandler(GemHandler):
    def __init__(self, address, port, active, session_id, name, custom_connection_handler=None,
                 initial_control_state="ATTEMPT_ONLINE", initial_online_control_state="REMOTE"):
        GemHandler.__init__(self, address, port, active, session_id, name, custom_connection_handler)

        self.isHost = False

        self.initialControlStates = ["EQUIPMENT_OFFLINE", "ATTEMPT_ONLINE", "HOST_OFFLINE", "ONLINE"]
        self.initialControlState = initial_control_state

        self.onlineControlStates = ["LOCAL", "REMOTE"]
        self.onlineControlState = initial_online_control_state

        self._time_format = 1

        self._data_values = {
        }

        self._status_variables = {
            SVID_CLOCK: StatusVariable(SVID_CLOCK, "Clock", "", SecsVarString),
            SVID_CONTROL_STATE: StatusVariable(SVID_CONTROL_STATE, "ControlState", "", SecsVarBinary),
            SVID_EVENTS_ENABLED: StatusVariable(SVID_EVENTS_ENABLED, "EventsEnabled", "", SecsVarArray),
            SVID_ALARMS_ENABLED: StatusVariable(SVID_ALARMS_ENABLED, "AlarmsEnabled", "", SecsVarArray),
            SVID_ALARMS_SET: StatusVariable(SVID_ALARMS_SET, "AlarmsSet", "", SecsVarArray),
        }

        self._collection_events = {
            CEID_EQUIPMENT_OFFLINE: CollectionEvent(CEID_EQUIPMENT_OFFLINE, "EquipmentOffline", []),
            CEID_CONTROL_STATE_LOCAL: CollectionEvent(CEID_CONTROL_STATE_LOCAL, "ControlStateLocal", []),
            CEID_CONTROL_STATE_REMOTE: CollectionEvent(CEID_CONTROL_STATE_REMOTE, "ControlStateRemote", []),
            CEID_CMD_START_DONE: CollectionEvent(CEID_CMD_START_DONE, "CmdStartDone", []),
            CEID_CMD_STOP_DONE: CollectionEvent(CEID_CMD_STOP_DONE, "CmdStopDone", []),
        }

        self._equipment_constants = {
            ECID_ESTABLISH_COMMUNICATIONS_TIMEOUT: EquipmentConstant(ECID_ESTABLISH_COMMUNICATIONS_TIMEOUT,
                                                                     "EstablishCommunicationsTimeout", 10, 120, 10,
                                                                     "sec", SecsVarI2),
            ECID_TIME_FORMAT: EquipmentConstant(ECID_TIME_FORMAT, "TimeFormat", 0, 2, 1, "", SecsVarI4),
        }

        self._alarms = {
        }

        self._remote_commands = {
            RCMD_START: RemoteCommand(RCMD_START, "Start", [], CEID_CMD_START_DONE),
            RCMD_STOP: RemoteCommand(RCMD_STOP, "Stop", [], CEID_CMD_STOP_DONE),
        }

        self._registered_reports = {}
        self._registered_collection_events = {}

        self.controlState = Fysom({
            'initial': "INIT",
            'events': [
                {'name': 'start', 'src': 'INIT', 'dst': 'CONTROL'},  # 1
                {'name': 'initial_offline', 'src': 'CONTROL', 'dst': 'OFFLINE'},  # 1
                {'name': 'initial_equipment_offline', 'src': 'OFFLINE', 'dst': 'EQUIPMENT_OFFLINE'},  # 2
                {'name': 'initial_attempt_online', 'src': 'OFFLINE', 'dst': 'ATTEMPT_ONLINE'},  # 2
                {'name': 'initial_host_offline', 'src': 'OFFLINE', 'dst': 'HOST_OFFLINE'},  # 2
                {'name': 'switch_online', 'src': 'EQUIPMENT_OFFLINE', 'dst': 'ATTEMPT_ONLINE'},  # 3
                {'name': 'attempt_online_fail_equipment_offline', 'src': 'ATTEMPT_ONLINE',
                 'dst': 'EQUIPMENT_OFFLINE'},  # 4
                {'name': 'attempt_online_fail_host_offline', 'src': 'ATTEMPT_ONLINE', 'dst': 'HOST_OFFLINE'},  # 4
                {'name': 'attempt_online_success', 'src': 'ATTEMPT_ONLINE', 'dst': 'ONLINE'},  # 5
                {'name': 'switch_offline', 'src': ["ONLINE", "ONLINE_LOCAL", "ONLINE_REMOTE"],
                 'dst': 'EQUIPMENT_OFFLINE'},  # 6, 12
                {'name': 'initial_online', 'src': 'CONTROL', 'dst': 'ONLINE'},  # 1
                {'name': 'initial_online_local', 'src': 'ONLINE', 'dst': 'ONLINE_LOCAL'},  # 7
                {'name': 'initial_online_remote', 'src': 'ONLINE', 'dst': 'ONLINE_REMOTE'},  # 7
                {'name': 'switch_online_local', 'src': 'ONLINE_REMOTE', 'dst': 'ONLINE_LOCAL'},  # 8
                {'name': 'switch_online_remote', 'src': 'ONLINE_LOCAL', 'dst': 'ONLINE_REMOTE'},  # 9
                {'name': 'remote_offline', 'src': ["ONLINE", "ONLINE_LOCAL", "ONLINE_REMOTE"],
                 'dst': 'HOST_OFFLINE'},  # 10
                {'name': 'remote_online', 'src': 'HOST_OFFLINE', 'dst': 'ONLINE'},  # 11
            ],
            'callbacks': {
                'onCONTROL': self._on_control_state_control,  # 1, forward online/offline depending on configuration
                'onOFFLINE': self._on_control_state_offline,  # 2, forward to configured offline state
                'onATTEMPT_ONLINE': self._on_control_state_attempt_online,  # 3, send S01E01
                'onONLINE': self._on_control_state_online,  # 7, forward to configured online state
                'oninitial_online_local': self._on_control_state_initial_online_local,  # 7, send collection event
                'onswitch_online_local': self._on_control_state_initial_online_local,  # 8, send collection event
                'oninitial_online_remote': self._on_control_state_initial_online_remote,  # 8, send collection event
                'onswitch_online_remote': self._on_control_state_initial_online_remote,  # 9, send collection event
            },
            'autoforward': [
            ]
        })

        self.controlState.start()
    def _on_control_state_control(self, _):
        if self.initialControlState == "ONLINE":
            self.controlState.initial_online()
        else:
            self.controlState.initial_offline()

    def _on_control_state_offline(self, _):
        if self.initialControlState == "EQUIPMENT_OFFLINE":
            self.controlState.initial_equipment_offline()
        elif self.initialControlState == "ATTEMPT_ONLINE":
            self.controlState.initial_attempt_online()
        elif self.initialControlState == "HOST_OFFLINE":
            self.controlState.initial_host_offline()

    def _on_control_state_attempt_online(self, _):
        if not self.communicationState.isstate("COMMUNICATING"):
            self.controlState.attempt_online_fail_host_offline()
            return

        response = self.are_you_there()

        if response is None:
            self.controlState.attempt_online_fail_host_offline()
            return

        if response.header.stream != 1 or response.header.function != 2:
            self.controlState.attempt_online_fail_host_offline()
            return

        self.controlState.attempt_online_success()

    def _on_control_state_online(self, _):
        if self.onlineControlState == "REMOTE":
            self.controlState.initial_online_remote()
        else:
            self.controlState.initial_online_local()

    def _on_control_state_initial_online_local(self, _):
        self.trigger_collection_events([CEID_CONTROL_STATE_LOCAL])

    def _on_control_state_initial_online_remote(self, _):
        self.trigger_collection_events([CEID_CONTROL_STATE_REMOTE])

    def control_switch_online(self):
        self.controlState.switch_online()

    def control_switch_offline(self):
        self.controlState.switch_offline()
        self.trigger_collection_events([CEID_EQUIPMENT_OFFLINE])

    def control_switch_online_local(self):
        self.controlState.switch_online_local()
        self.onlineControlState = "LOCAL"

    def control_switch_online_remote(self):
        self.controlState.switch_online_remote()
        self.onlineControlState = "REMOTE"

    def _on_s01f15(self, handler, packet):
        del handler, packet  # unused parameters

        OFLACK = 0

        if self.controlState.current in ["ONLINE", "ONLINE_LOCAL", "ONLINE_REMOTE"]:
            self.controlState.remote_offline()
            self.trigger_collection_events([CEID_EQUIPMENT_OFFLINE])

        return self.stream_function(1, 16)(OFLACK)

    def _on_s01f17(self, handler, packet):
        del handler, packet  # unused parameters

        ONLACK = 1

        if self.controlState.isstate("HOST_OFFLINE"):
            self.controlState.remote_online()
            ONLACK = 0
        elif self.controlState.current in ["ONLINE", "ONLINE_LOCAL", "ONLINE_REMOTE"]:
            ONLACK = 2

        return self.stream_function(1, 18)(ONLACK)

    # data values

    @property
    def data_values(self):
        return self._data_values

    def on_dv_value_request(self, dvid, dv):
        del dvid  # unused variable

        return dv.value_type(dv.value)

    def _get_dv_value(self, dv):
        if dv.use_callback:
            return self.on_dv_value_request(dv.id_type(dv.dvid), dv)

        return dv.value_type(dv.value)

    # status variables

    @property
    def status_variables(self):
        return self._status_variables

    def on_sv_value_request(self, svid, sv):
        del svid  # unused variable

        return sv.value_type(sv.value)

    def _get_sv_value(self, sv):
        if sv.svid == SVID_CLOCK:
            return sv.value_type(self._get_clock())
        if sv.svid == SVID_CONTROL_STATE:
            return sv.value_type(self._get_control_state_id())
        if sv.svid == SVID_EVENTS_ENABLED:
            events = self._get_events_enabled()
            return sv.value_type(SV, events)
        if sv.svid == SVID_ALARMS_ENABLED:
            alarms = self._get_alarms_enabled()
            return sv.value_type(SV, alarms)
        if sv.svid == SVID_ALARMS_SET:
            alarms = self._get_alarms_set()
            return sv.value_type(SV, alarms)

        if sv.use_callback:
            return self.on_sv_value_request(sv.id_type(sv.svid), sv)

        return sv.value_type(sv.value)

    def _on_s01f03(self, handler, packet):
        del handler  # unused parameters

        message = self.secs_decode(packet)

        responses = []

        if len(message) == 0:
            for svid in self._status_variables:
                sv = self._status_variables[svid]
                responses.append(self._get_sv_value(sv))
        else:
            for svid in message:
                if svid not in self._status_variables:
                    responses.append(SecsVarArray(SV, []))
                else:
                    sv = self._status_variables[svid]
                    responses.append(self._get_sv_value(sv))

        return self.stream_function(1, 4)(responses)

    def _on_s01f11(self, handler, packet):
        del handler  # unused parameters

        message = self.secs_decode(packet)

        responses = []

        if len(message) == 0:
            for svid in self._status_variables:
                sv = self._status_variables[svid]
                responses.append({"SVID": sv.svid, "SVNAME": sv.name, "UNITS": sv.unit})
        else:
            for svid in message:
                if svid not in self._status_variables:
                    responses.append({"SVID": svid, "SVNAME": "", "UNITS": ""})
                else:
                    sv = self._status_variables[svid]
                    responses.append({"SVID": sv.svid, "SVNAME": sv.name, "UNITS": sv.unit})

        return self.stream_function(1, 12)(responses)

    # collection events

    @property
    def collection_events(self):
        return self._collection_events

    @property
    def registered_reports(self):
        return self._registered_reports

    @property
    def registered_collection_events(self):
        return self._registered_collection_events

    def trigger_collection_events(self, ceids):
        if not isinstance(ceids, list):
            ceids = [ceids]

        for ceid in ceids:
            if ceid in self._registered_collection_events:
                if self._registered_collection_events[ceid].enabled:
                    reports = self._build_collection_event(ceid)

                    self.send_and_waitfor_response(self.stream_function(6, 11)(
                        {"DATAID": 1, "CEID": ceid, "RPT": reports}))

    def _on_s02f33(self, handler, packet):
        del handler  # unused parameters

        message = self.secs_decode(packet)

        # 0  = Accept
        # 1  = Denied. Insufficient space.
        # 2  = Denied. Invalid format.
        # 3  = Denied. At least one RPTID already defined.
        # 4  = Denied. At least VID does not exist.
        # >4 = Other errors
        DRACK = 0

        # pre check message for errors
        for report in message.DATA:
            if report.RPTID in self._registered_reports and len(report.VID) > 0:
                DRACK = 3
            else:
                for vid in report.VID:
                    if (vid not in self._data_values) and (vid not in self._status_variables):
                        DRACK = 4

        # pre check okay
        if DRACK == 0:
            # no data -> remove all reports and links
            if not message.DATA:
                self._registered_collection_events.clear()
                self._registered_reports.clear()
            else:
                for report in message.DATA:
                    # no vids -> remove this reports and links
                    if not report.VID:
                        # remove report from linked collection events
                        for collection_event in list(self._registered_collection_events):
                            if report.RPTID in self._registered_collection_events[collection_event].reports:
                                self._registered_collection_events[collection_event].reports.remove(report.RPTID)
                                # remove collection event link if no collection events present
                                if not self._registered_collection_events[collection_event].reports:
                                    del self._registered_collection_events[collection_event]
                        # remove report
                        if report.RPTID in self._registered_reports:
                            del self._registered_reports[report.RPTID]
                    else:
                        # add report
                        self._registered_reports[report.RPTID] = CollectionEventReport(report.RPTID, report.VID)

        return self.stream_function(2, 34)(DRACK)

    def _on_s02f35(self, handler, packet):
        del handler  # unused parameters

        message = self.secs_decode(packet)

        # 0  = Accepted
        # 1  = Denied. Insufficient space
        # 2  = Denied. Invalid format
        # 3  = Denied. At least one CEID link already defined
        # 4  = Denied. At least one CEID does not exist
        # 5  = Denied. At least one RPTID does not exist
        # >5 = Other errors
        LRACK = 0

        # pre check message for errors
        for event in message.DATA:
            if event.CEID.get() not in self._collection_events:
                LRACK = 4
            for rptid in event.RPTID:
                if event.CEID.get() in self._registered_collection_events:
                    ce = self._registered_collection_events[event.CEID.get()]
                    if rptid.get() in ce.reports:
                        LRACK = 3
                if rptid.get() not in self._registered_reports:
                    LRACK = 5

        # pre check okay
        if LRACK == 0:
            for event in message.DATA:
                # no report ids, remove all links for collection event
                if not event.RPTID:
                    if event.CEID.get() in self._registered_collection_events:
                        del self._registered_collection_events[event.CEID.get()]
                else:
                    if event.CEID.get() in self._registered_collection_events:
                        ce = self._registered_collection_events[event.CEID.get()]
                        for rptid in event.RPTID.get():
                            ce.reports.append(rptid)
                    else:
                        self._registered_collection_events[event.CEID.get()] = \
                            CollectionEventLink(self._collection_events[event.CEID.get()], event.RPTID.get())

        return self.stream_function(2, 36)(LRACK)

    def _on_s02f37(self, handler, packet):
        del handler  # unused parameters

        message = self.secs_decode(packet)

        # 0  = Accepted
        # 1  = Denied. At least one CEID does not exist
        ERACK = 0

        if not self._set_ce_state(message.CEED.get(), message.CEID.get()):
            ERACK = 1

        return self.stream_function(2, 38)(ERACK)

    def _on_s06f15(self, handler, packet):
        del handler  # unused parameters

        message = self.secs_decode(packet)

        ceid = message.get()

        reports = []

        if ceid in self._registered_collection_events:
            if self._registered_collection_events[ceid].enabled:
                reports = self._build_collection_event(ceid)

        return self.stream_function(6, 16)({"DATAID": 1, "CEID": ceid, "RPT": reports})

    def _set_ce_state(self, ceed, ceids):
        result = True
        if not ceids:
            for ceid in self._registered_collection_events:
                self._registered_collection_events[ceid].enabled = ceed
        else:
            for ceid in ceids:
                if ceid in self._registered_collection_events:
                    self._registered_collection_events[ceid].enabled = ceed
                else:
                    result = False

        return result

    def _build_collection_event(self, ceid):
        reports = []

        for rptid in self._registered_collection_events[ceid].reports:
            report = self._registered_reports[rptid]
            variables = []
            for var in report.vars:
                if var in self._status_variables:
                    v = self._get_sv_value(self._status_variables[var])
                    variables.append(v)
                elif var in self._data_values:
                    v = self._get_dv_value(self._data_values[var])
                    variables.append(v)

            reports.append({"RPTID": rptid, "V": variables})

        return reports

    # equipment constants

    @property
    def equipment_constants(self):
        return self._equipment_constants

    def on_ec_value_request(self, ecid, ec):
        del ecid  # unused variable

        return ec.value_type(ec.value)

    def on_ec_value_update(self, ecid, ec, value):
        del ecid  # unused variable

        ec.value = value

    def _get_ec_value(self, ec):
        if ec.ecid == ECID_ESTABLISH_COMMUNICATIONS_TIMEOUT:
            return ec.value_type(self.establishCommunicationTimeout)
        if ec.ecid == ECID_TIME_FORMAT:
            return ec.value_type(self._time_format)

        if ec.use_callback:
            return self.on_ec_value_request(ec.id_type(ec.ecid), ec)
        return ec.value_type(ec.value)

    def _set_ec_value(self, ec, value):
        if ec.ecid == ECID_ESTABLISH_COMMUNICATIONS_TIMEOUT:
            self.establishCommunicationTimeout = value
        if ec.ecid == ECID_TIME_FORMAT:
            self._time_format = value

        if ec.use_callback:
            self.on_ec_value_update(ec.id_type(ec.ecid), ec, value)
        else:
            ec.value = value

    def _on_s02f13(self, handler, packet):
        del handler  # unused parameters

        message = self.secs_decode(packet)

        responses = []

        if len(message) == 0:
            for ecid in self._equipment_constants:
                ec = self._equipment_constants[ecid]
                responses.append(self._get_ec_value(ec))
        else:
            for ecid in message:
                if ecid not in self._equipment_constants:
                    responses.append(SecsVarArray(ECV, []))
                else:
                    ec = self._equipment_constants[ecid]
                    responses.append(self._get_ec_value(ec))

        return self.stream_function(2, 14)(responses)

    def _on_s02f15(self, handler, packet):
        del handler  # unused parameters

        message = self.secs_decode(packet)

        eac = 0

        for ec in message:
            if ec.ECID not in self._equipment_constants:
                eac = 1
            else:
                constant = self.equipment_constants[ec.ECID.get()]

                if constant.min_value is not None:
                    if ec.ECV.get() < constant.min_value:
                        eac = 3

                if constant.max_value is not None:
                    if ec.ECV.get() > constant.max_value:
                        eac = 3

        if eac == 0:
            for ec in message:
                self._set_ec_value(self._equipment_constants[ec.ECID], ec.ECV)

        return self.stream_function(2, 16)(eac)

    def _on_s02f29(self, handler, packet):
        del handler  # unused parameters

        message = self.secs_decode(packet)

        responses = []

        if len(message) == 0:
            for ecid in self._equipment_constants:
                ec = self._equipment_constants[ecid]
                responses.append({"ECID": ec.ecid, "ECNAME": ec.name,
                                  "ECMIN": ec.min_value if ec.min_value is not None else "",
                                  "ECMAX": ec.max_value if ec.max_value is not None else "",
                                  "ECDEF": ec.default_value, "UNITS": ec.unit})
        else:
            for ecid in message:
                if ecid not in self._equipment_constants:
                    responses.append({"ECID": ecid, "ECNAME": "", "ECMIN": "", "ECMAX": "", "ECDEF": "", "UNITS": ""})
                else:
                    ec = self._equipment_constants[ecid]
                    responses.append({"ECID": ec.ecid, "ECNAME": ec.name,
                                      "ECMIN": ec.min_value if ec.min_value is not None else "",
                                      "ECMAX": ec.max_value if ec.max_value is not None else "",
                                      "ECDEF": ec.default_value, "UNITS": ec.unit})

        return self.stream_function(2, 30)(responses)

    # alarms

    @property
    def alarms(self):
        return self._alarms

    def set_alarm(self, alid):
        if alid not in self.alarms:
            raise ValueError("Unknown alarm id {}".format(alid))

        if self.alarms[alid].set:
            return

        if self.alarms[alid].enabled:
            self.send_and_waitfor_response(self.stream_function(5, 1)({"ALCD": self.alarms[alid].code | ALCD.ALARM_SET,
                                                                       "ALID": alid, "ALTX": self.alarms[alid].text}))

        self.alarms[alid].set = True

        self.trigger_collection_events([self.alarms[alid].ce_on])

    def clear_alarm(self, alid):
        if alid not in self.alarms:
            raise ValueError("Unknown alarm id {}".format(alid))

        if not self.alarms[alid].set:
            return

        if self.alarms[alid].enabled:
            self.send_and_waitfor_response(self.stream_function(5, 1)({"ALCD": self.alarms[alid].code,
                                                                       "ALID": alid, "ALTX": self.alarms[alid].text}))

        self.alarms[alid].set = False

        self.trigger_collection_events([self.alarms[alid].ce_off])

    def _on_s05f03(self, handler, packet):
        del handler  # unused parameters

        message = self.secs_decode(packet)

        result = ACKC5.ACCEPTED

        alid = message.ALID.get()
        if alid not in self._alarms:
            result = ACKC5.ERROR
        else:
            self.alarms[alid].enabled = (message.ALED.get() == ALED.ENABLE)

        return self.stream_function(5, 4)(result)

    def _on_s05f05(self, handler, packet):
        del handler  # unused parameters

        message = self.secs_decode(packet)

        result = []

        alids = message.get()

        if len(alids) == 0:
            alids = list(self.alarms.keys())

        for alid in alids:
            result.append({"ALCD": self.alarms[alid].code | (ALCD.ALARM_SET if self.alarms[alid].set else 0),
                           "ALID": alid, "ALTX": self.alarms[alid].text})

        return self.stream_function(5, 6)(result)

    def _on_s05f07(self, handler, packet):
        del handler, packet  # unused parameters

        result = []

        for alid in list(self.alarms.keys()):
            if self.alarms[alid].enabled:
                result.append({"ALCD": self.alarms[alid].code | (ALCD.ALARM_SET if self.alarms[alid].set else 0),
                               "ALID": alid, "ALTX": self.alarms[alid].text})

        return self.stream_function(5, 8)(result)

    # remote commands

    @property
    def remote_commands(self):
        return self._remote_commands

    def _on_s02f41(self, handler, packet):
        del handler  # unused parameters

        message = self.secs_decode(packet)

        rcmd_name = message.RCMD.get()
        rcmd_callback_name = "rcmd_" + rcmd_name

        if rcmd_name not in self._remote_commands:
            return self.stream_function(2, 42)({"HCACK": HCACK.INVALID_COMMAND, "PARAMS": []})

        if rcmd_callback_name not in self._callback_handler:
            return self.stream_function(2, 42)({"HCACK": HCACK.INVALID_COMMAND, "PARAMS": []})

        for param in message.PARAMS:
            if param.CPNAME.get() not in self._remote_commands[rcmd_name].params:
                return self.stream_function(2, 42)({"HCACK": HCACK.PARAMETER_INVALID, "PARAMS": []})

        self.send_response(self.stream_function(2, 42)({"HCACK": HCACK.ACK_FINISH_LATER, "PARAMS": []}),
                           packet.header.system)

        callback = getattr(self._callback_handler, rcmd_callback_name)

        kwargs = {}
        for param in message.PARAMS.get():
            kwargs[param['CPNAME']] = param['CPVAL']

        callback(**kwargs)

        self.trigger_collection_events([self._remote_commands[rcmd_name].ce_finished])

        return None

    def _get_clock(self):
        now = datetime.now()
        if self._time_format == 0:
            return now.strftime("%y%m%d%H%M%S")

        if self._time_format == 2:
            return now.isoformat()

        return now.strftime("%Y%m%d%H%M%S") + now.strftime("%f")[0:2]

    def _get_control_state_id(self):
        if self.controlState.isstate("EQUIPMENT_OFFLINE"):
            return 1
        if self.controlState.isstate("ATTEMPT_ONLINE"):
            return 2
        if self.controlState.isstate("HOST_OFFLINE"):
            return 3
        if self.controlState.isstate("ONLINE_LOCAL"):
            return 4
        if self.controlState.isstate("ONLINE_REMOTE"):
            return 5

        return -1

    def _get_events_enabled(self):
        enabled_ceid = []

        for ceid in self._registered_collection_events:
            if self._registered_collection_events[ceid].enabled:
                enabled_ceid.append(ceid)

        return enabled_ceid

    def _get_alarms_enabled(self):
        enabled_alarms = []

        for alid in self._alarms:
            if self._alarms[alid].enabled:
                enabled_alarms.append(alid)

        return enabled_alarms

    def _get_alarms_set(self):
        set_alarms = []

        for alid in self._alarms:
            if self._alarms[alid].set:
                set_alarms.append(alid)

        return set_alarms

    def on_connection_closed(self, connection):
        # call parent handlers
        GemHandler.on_connection_closed(self, connection)

        # update control state
        if self.controlState.current in ["ONLINE", "ONLINE_LOCAL", "ONLINE_REMOTE"]:
            self.controlState.switch_offline()

        if self.controlState.current in ["EQUIPMENT_OFFLINE"]:
            self.controlState.switch_online()


class GemHostHandler(GemHandler):
    """Baseclass for creating host models. Inherit from this class and override required functions."""

    def __init__(self, address, port, active, session_id, name, custom_connection_handler=None):
        """
        Initialize a gem host handler.

        :param address: IP address of remote host
        :type address: string
        :param port: TCP port of remote host
        :type port: integer
        :param active: Is the connection active (*True*) or passive (*False*)
        :type active: boolean
        :param session_id: session / device ID to use for connection
        :type session_id: integer
        :param name: Name of the underlying configuration
        :type name: string
        :param custom_connection_handler: object for connection handling (ie multi server)
        :type custom_connection_handler: :class:`secsgem.hsms.connections.HsmsMultiPassiveServer`
        """
        GemHandler.__init__(self, address, port, active, session_id, name, custom_connection_handler)

        self.isHost = True

        self.reportSubscriptions = {}

    def clear_collection_events(self):
        """Clear all collection events."""
        self.secsgem_logging("Clearing collection events", 'trace')

        # clear subscribed reports
        self.reportSubscriptions = {}

        # disable all ceids
        self.disable_ceids()

        # delete all reports
        self.disable_ceid_reports()

    def subscribe_collection_event(self, ceid, dvs, report_id=None):
        """
        Subscribe to a collection event.

        :param ceid: ID of the collection event
        :type ceid: integer
        :param dvs: DV IDs to add for collection event
        :type dvs: list of integers
        :param report_id: optional - ID for report, autonumbering if None
        :type report_id: integer
        """
        self.secsgem_logging("Subscribing to collection event {}".format(ceid), 'trace')

        if report_id is None:
            report_id = self.reportIDCounter
            self.reportIDCounter += 1

        # note subscribed reports
        self.reportSubscriptions[report_id] = dvs

        # create report
        self.send_and_waitfor_response(self.stream_function(2, 33)(
            {"DATAID": 0, "DATA": [{"RPTID": report_id, "VID": dvs}]}))

        # link event report to collection event
        self.send_and_waitfor_response(self.stream_function(2, 35)(
            {"DATAID": 0, "DATA": [{"CEID": ceid, "RPTID": [report_id]}]}))

        # enable collection event
        self.send_and_waitfor_response(self.stream_function(2, 37)({"CEED": True, "CEID": [ceid]}))

    def send_remote_command(self, rcmd, params):
        """
        Send a remote command.

        :param rcmd: Name of command
        :type rcmd: string
        :param params: DV IDs to add for collection event
        :type params: list of strings
        """
        self.secsgem_logging("Send RCMD {}".format(rcmd), 'trace')

        s2f41 = self.stream_function(2, 41)()
        s2f41.RCMD = rcmd
        if isinstance(params, list):
            for param in params:
                s2f41.PARAMS.append({"CPNAME": param[0], "CPVAL": param[1]})
        elif isinstance(params, OrderedDict):
            for param in params:
                s2f41.PARAMS.append({"CPNAME": param, "CPVAL": params[param]})

        # send remote command
        return self.secs_decode(self.send_and_waitfor_response(s2f41))

    def delete_process_programs(self, ppids):
        """
        Delete a list of process program.

        :param ppids: Process programs to delete
        :type ppids: list of strings
        """
        self.secsgem_logging("Delete process programs {}".format(ppids), 'trace')

        # send remote command
        return self.secs_decode(self.send_and_waitfor_response(self.stream_function(7, 17)(ppids))).get()

    def get_process_program_list(self):
        """Get process program list."""
        self.secsgem_logging("Get process program list", 'trace')

        # send remote command
        return self.secs_decode(self.send_and_waitfor_response(self.stream_function(7, 19)())).get()

    def go_online(self):
        """Set control state to online."""
        self.secsgem_logging("Go online", 'trace')

        # send remote command
        return self.secs_decode(self.send_and_waitfor_response(self.stream_function(1, 17)())).get()

    def go_offline(self):
        """Set control state to offline."""
        self.secsgem_logging("Go offline", 'trace')

        # send remote command
        return self.secs_decode(self.send_and_waitfor_response(self.stream_function(1, 15)())).get()

    def enable_alarm(self, alid):
        """
        Enable alarm.

        :param alid: alarm id to enable
        :type alid: :class:`secsgem.secs.dataitems.ALID`
        """
        self.secsgem_logging("Enable alarm {}".format(alid), 'trace')

        return self.secs_decode(self.send_and_waitfor_response(self.stream_function(5, 3)(
            {"ALED": ALED.ENABLE, "ALID": alid}))).get()

    def disable_alarm(self, alid):
        """
        Disable alarm.

        :param alid: alarm id to disable
        :type alid: :class:`secsgem.secs.dataitems.ALID`
        """
        self.secsgem_logging("Disable alarm {}".format(alid), 'trace')

        return self.secs_decode(self.send_and_waitfor_response(self.stream_function(5, 3)(
            {"ALED": ALED.DISABLE, "ALID": alid}))).get()

    def list_alarms(self, alids=None):
        """
        List alarms.

        :param alids: alarms to list details for
        :type alids: array of int/str
        """
        if alids is None:
            alids = []
            self.secsgem_logging("List all alarms", 'trace')
        else:
            self.secsgem_logging("List alarms {}".format(alids), 'trace')

        return self.secs_decode(self.send_and_waitfor_response(self.stream_function(5, 5)(alids))).get()

    def list_enabled_alarms(self):
        """List enabled alarms."""
        self.secsgem_logging("List all enabled alarms", 'trace')

        return self.secs_decode(self.send_and_waitfor_response(self.stream_function(5, 7)())).get()

    def _on_alarm_received(self, handler, ALID, ALCD, ALTX):
        del handler, ALID, ALCD, ALTX  # unused variables
        return ACKC5.ACCEPTED

    def _on_s05f01(self, handler, packet):
        """
        Callback handler for Stream 5, Function 1, Alarm request.

        :param handler: handler the message was received on
        :type handler: :class:`secsgem.hsms.handler.HsmsHandler`
        :param packet: complete message received
        :type packet: :class:`secsgem.hsms.packets.HsmsPacket`
        """
        s5f1 = self.secs_decode(packet)

        result = self._callback_handler.alarm_received(handler, s5f1.ALID, s5f1.ALCD, s5f1.ALTX)

        self.events.fire("alarm_received", {"code": s5f1.ALCD, "alid": s5f1.ALID, "text": s5f1.ALTX,
                                            "handler": self.connection, 'peer': self})

        return self.stream_function(5, 2)(result)

    def _on_s06f11(self, handler, packet):
        """
        Callback handler for Stream 6, Function 11, Establish Communication Request.

        :param handler: handler the message was received on
        :type handler: :class:`secsgem.hsms.handler.HsmsHandler`
        :param packet: complete message received
        :type packet: :class:`secsgem.hsms.packets.HsmsPacket`
        """
        del handler  # unused parameters

        message = self.secs_decode(packet)

        for report in message.RPT:
            report_dvs = self.reportSubscriptions[report.RPTID.get()]
            report_values = report.V.get()

            values = []

            for i, s in enumerate(report_dvs):
                values.append({"dvid": s, "value": report_values[i], "name": self.get_dvid_name(s)})

            data = {"ceid": message.CEID, "rptid": report.RPTID, "values": values,
                    "name": self.get_ceid_name(message.CEID), "handler": self.connection, 'peer': self}
            self.events.fire("collection_event_received", data)

        return self.stream_function(6, 12)(0)

    def _on_terminal_received(self, handler, TID, TEXT):
        del handler, TID, TEXT  # unused variables
        return ACKC10.ACCEPTED

    def _on_s10f01(self, handler, packet):
        """
        Callback handler for Stream 10, Function 1, Terminal Request.

        :param handler: handler the message was received on
        :type handler: :class:`secsgem.hsms.handler.HsmsHandler`
        :param packet: complete message received
        :type packet: :class:`secsgem.hsms.packets.HsmsPacket`
        """
        s10f1 = self.secs_decode(packet)

        result = self._callback_handler.terminal_received(handler, s10f1.TID, s10f1.TEXT)
        self.events.fire("terminal_received", {"text": s10f1.TEXT, "terminal": s10f1.TID, "handler": self.connection,
                                               'peer': self})

        return self.stream_function(10, 2)(result)


class DVVALNAME(DataItemBase):
    __type__ = SecsVarString

class CENAME(DataItemBase):
    __type__ = SecsVarString

class CMDA(DataItemBase):
    __type__ = SecsVarU1

class TIAACK(DataItemBase):
    __type__ = SecsVarBinary

class ABS(DataItemBase):
    __type__ = SecsVarDynamic
    __allowedtypes__ = [SecsVarU1, SecsVarU2, SecsVarU4, SecsVarU8, SecsVarI1, SecsVarI2, SecsVarI4, SecsVarI8,
                        SecsVarString, SecsVarText, SecsVarJIS8,
                        SecsVarArray, SecsVarList,
                        SecsVarBinary, SecsVarBoolean, SecsVarF4, SecsVarF8, SecsVarNumber]

class TIACK(DataItemBase):
    __type__ = SecsVarBinary

class GRANT(DataItemBase):
    __type__ = SecsVarBinary

class STRID(DataItemBase):
    __type__ = SecsVarU1

class FCNID(DataItemBase):
    __type__ = SecsVarU1

class RSPACK(DataItemBase):
    __type__ = SecsVarBinary

class STRACK(DataItemBase):
    __type__ = SecsVarBinary
    
class TRID(DataItemBase):
    __type__ = SecsVarU4

class SMPLN(DataItemBase):
    __type__ = SecsVarU4

class DSPER(DataItemBase):
    __type__ = SecsVarString

class TOTSMP(DataItemBase):
    __type__ = SecsVarU4

class REPGSZ(DataItemBase):
    __type__ = SecsVarU4

class STIME(DataItemBase):
    __type__ = SecsVarString

class SecsS01F21(SecsStreamFunction):
    _stream = 1
    _function = 21

    _dataFormat = [VID]

    _isReplyRequired = True

class SecsS01F22(SecsStreamFunction):
    _stream = 1
    _function = 22

    _dataFormat = [[VID, DVVALNAME, UNITS]]

class SecsS01F23(SecsStreamFunction):
    _stream = 1
    _function = 23

    _dataFormat = [CEID]

    _isReplyRequired = True

class SecsS01F24(SecsStreamFunction):
    _stream = 1
    _function = 24

    _dataFormat = [[CEID, CENAME, [VID]]]

class SecsS02F21(SecsStreamFunction):
    _stream = 2
    _function = 21

    _dataFormat = RCMD

    _isReplyRequired = True

class SecsS02F22(SecsStreamFunction):
    _stream = 2
    _function = 22

    _dataFormat = CMDA

class SecsS02F23(SecsStreamFunction):
    _stream = 2
    _function = 23

    _dataFormat = [
        TRID,
        DSPER,
        TOTSMP,
        REPGSZ,
        [SVID]
    ]

    _isReplyRequired = True

class SecsS02F24(SecsStreamFunction):
    _stream = 2
    _function = 24

    _dataFormat = TIAACK

class SecsS02F25(SecsStreamFunction):
    _stream = 2
    _function = 25

    _dataFormat = ABS

    _isReplyRequired = True

class SecsS02F26(SecsStreamFunction):
    _stream = 2
    _function = 26

    _dataFormat = ABS

    _isReplyRequired = True

class SecsS02F31(SecsStreamFunction):
    _stream = 2
    _function = 31

    _dataFormat = TIME

    _isReplyRequired = True

class SecsS02F32(SecsStreamFunction):
    _stream = 2
    _function = 32

    _dataFormat = TIACK

class SecsS02F39(SecsStreamFunction):
    _stream = 2
    _function = 39

    _dataFormat = [DATAID, DATALENGTH]

    _isReplyRequired = True

class SecsS02F40(SecsStreamFunction):
    _stream = 2
    _function = 40

    _dataFormat = GRANT

class SecsS02F43(SecsStreamFunction):
    _stream = 2
    _function = 43

    _dataFormat = [[STRID,[FCNID]]]

    _isReplyRequired = True

class SecsS02F44(SecsStreamFunction):
    _stream = 2
    _function = 44

    _dataFormat = [RSPACK,[[STRID, STRACK, [FCNID]]]]

class SecsS06F01(SecsStreamFunction):
    _stream = 6
    _function = 1

    _dataFormat = [
        TRID,
        SMPLN,
        STIME,
        [SV]
    ]

    _isReplyRequired = True

class SecsS06F02(SecsStreamFunction):
    _stream = 6
    _function = 2
    _dataFormat = ACKC6

class SecsGemClientBase(GemHostHandler):
    _session_id = 0
    def __init__(self, address, port, name, secsgem_protocol_connection_lost):
        GemHostHandler.__init__(self, address, port, True, self._session_id, name, None)
        self._secsgem_protocol_connection_lost = secsgem_protocol_connection_lost
        self.secsStreamsFunctions[1].update({
            21:SecsS01F21,
            22:SecsS01F22,
            23:SecsS01F23,
            24:SecsS01F24
        })
        self.secsStreamsFunctions[2].update({
            21:SecsS02F21,
            22:SecsS02F22,
            23:SecsS02F23,
            24:SecsS02F24,
            25:SecsS02F25,
            26:SecsS02F26,
            31:SecsS02F31,
            32:SecsS02F32,
            39:SecsS02F39,
            40:SecsS02F40,
            43:SecsS02F43,
            44:SecsS02F44
        })
        self.secsStreamsFunctions[6].update({
            1:SecsS06F01,
            2:SecsS06F02
        })
    def _on_event_hsms_disconnected(self, args):
        self._secsgem_protocol_connection_lost()
    def _on_s01f14(self, handler, packet):
        self.secsgem_logging('S1F14 received', 'debug')

    def secsgem_logging(self, log_msg, lvl):
        if lvl == 'info':
            secsgem_logger.info('[{}] {}', self.name, log_msg)
        elif lvl == 'debug':
            secsgem_logger.debug('[{}] {}', self.name, log_msg)
        elif lvl == 'trace':
            secsgem_logger.trace('[{}] {}', self.name, log_msg)
        elif lvl == 'warn':
            secsgem_logger.warn('[{}] {}', self.name, log_msg)
        else:
            secsgem_logger.error('[{}] {}', self.name, log_msg)

class SecsGemServerBase(GemEquipmentHandler):
    _session_id = 1
    def __init__(self, address, port, name):
        GemEquipmentHandler.__init__(self, address, port, False, self._session_id, name, None)
        self.secsStreamsFunctions[1].update({
            21:SecsS01F21,
            22:SecsS01F22,
            23:SecsS01F23,
            24:SecsS01F24
        })
        self.secsStreamsFunctions[2].update({
            21:SecsS02F21,
            22:SecsS02F22,
            23:SecsS02F23,
            24:SecsS02F24,
            25:SecsS02F25,
            26:SecsS02F26,
            31:SecsS02F31,
            32:SecsS02F32,
            39:SecsS02F39,
            40:SecsS02F40,
            43:SecsS02F43,
            44:SecsS02F44
        })
        self.secsStreamsFunctions[6].update({
            1:SecsS06F01,
            2:SecsS06F02
        })
    def _on_s01f14(self, handler, packet):
        self.secsgem_logging('S1F14 received', 'debug')

    def secsgem_logging(self, log_msg, lvl):
        if lvl == 'info':
            secsgem_logger.info('[{}] {}', self.name, log_msg)
        elif lvl == 'debug':
            secsgem_logger.debug('[{}] {}', self.name, log_msg)
        elif lvl == 'trace':
            secsgem_logger.trace('[{}] {}', self.name, log_msg)
        elif lvl == 'warn':
            secsgem_logger.warn('[{}] {}', self.name, log_msg)
        else:
            secsgem_logger.error('[{}] {}', self.name, log_msg)
